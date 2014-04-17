# Copyright 2012 Google Inc. All Rights Reserved.

"""Base and helper classes for Google RESTful APIs."""





__all__ = ['add_sync_methods']

import httplib
import time

from . import api_utils

try:
  from google.appengine.api import app_identity
  from google.appengine.ext import ndb
except ImportError:
  from google.appengine.api import app_identity
  from google.appengine.ext import ndb


def _make_sync_method(name):
  """Helper to synthesize a synchronous method from an async method name.

  Used by the @add_sync_methods class decorator below.

  Args:
    name: The name of the synchronous method.

  Returns:
    A method (with first argument 'self') that retrieves and calls
    self.<name>, passing its own arguments, expects it to return a
    Future, and then waits for and returns that Future's result.
  """

  def sync_wrapper(self, *args, **kwds):
    method = getattr(self, name)
    future = method(*args, **kwds)
    return future.get_result()

  return sync_wrapper


def add_sync_methods(cls):
  """Class decorator to add synchronous methods corresponding to async methods.

  This modifies the class in place, adding additional methods to it.
  If a synchronous method of a given name already exists it is not
  replaced.

  Args:
    cls: A class.

  Returns:
    The same class, modified in place.
  """
  for name in cls.__dict__.keys():
    if name.endswith('_async'):
      sync_name = name[:-6]
      if not hasattr(cls, sync_name):
        setattr(cls, sync_name, _make_sync_method(name))
  return cls


class _AE_TokenStorage_(ndb.Model):
  """Entity to store app_identity tokens in memcache."""

  token = ndb.StringProperty()
  expires = ndb.FloatProperty()


@ndb.tasklet
def _make_token_async(scopes, service_account_id):
  """Get a fresh authentication token.

  Args:
    scopes: A list of scopes.
    service_account_id: Internal-use only.

  Returns:
    An tuple (token, expiration_time) where expiration_time is
    seconds since the epoch.
  """
  rpc = app_identity.create_rpc()
  app_identity.make_get_access_token_call(rpc, scopes, service_account_id)
  token, expires_at = yield rpc
  raise ndb.Return((token, expires_at))


class _RestApi(object):
  """Base class for REST-based API wrapper classes.

  This class manages authentication tokens and request retries.  All
  APIs are available as synchronous and async methods; synchronous
  methods are synthesized from async ones by the add_sync_methods()
  function in this module.

  WARNING: Do NOT directly use this api. It's an implementation detail
  and is subject to change at any release.
  """

  def __init__(self, scopes, service_account_id=None, token_maker=None,
               retry_params=None):
    """Constructor.

    Args:
      scopes: A scope or a list of scopes.
      token_maker: An asynchronous function of the form
        (scopes, service_account_id) -> (token, expires).
      retry_params: An instance of api_utils.RetryParams. If None, the
        default for current thread will be used.
      service_account_id: Internal use only.
    """

    if isinstance(scopes, basestring):
      scopes = [scopes]
    self.scopes = scopes
    self.service_account_id = service_account_id
    self.make_token_async = token_maker or _make_token_async
    self.token = None
    if not retry_params:
      retry_params = api_utils._get_default_retry_params()
    self.retry_params = retry_params

  def __getstate__(self):
    """Store state as part of serialization/pickling."""
    return {'token': self.token,
            'scopes': self.scopes,
            'id': self.service_account_id,
            'a_maker': None if self.make_token_async == _make_token_async
            else self.make_token_async,
            'retry_params': self.retry_params}

  def __setstate__(self, state):
    """Restore state as part of deserialization/unpickling."""
    self.__init__(state['scopes'],
                  service_account_id=state['id'],
                  token_maker=state['a_maker'],
                  retry_params=state['retry_params'])
    self.token = state['token']

  @ndb.tasklet
  def do_request_async(self, url, method='GET', headers=None, payload=None,
                       deadline=None, callback=None):
    """Issue one HTTP request.

    This is an async wrapper around urlfetch(). It adds an authentication
    header and retries on a 401 status code. Upon other retriable errors,
    it performs blocking retries.
    """
    headers = {} if headers is None else dict(headers)
    if self.token is None:
      self.token = yield self.get_token_async()
    headers['authorization'] = 'OAuth ' + self.token

    deadline = deadline or self.retry_params.urlfetch_timeout

    retry = False
    resp = None
    try:
      resp = yield self.urlfetch_async(url, payload=payload, method=method,
                                       headers=headers, follow_redirects=False,
                                       deadline=deadline, callback=callback)
      if resp.status_code == httplib.UNAUTHORIZED:
        self.token = yield self.get_token_async(refresh=True)
        headers['authorization'] = 'OAuth ' + self.token
        resp = yield self.urlfetch_async(
            url, payload=payload, method=method, headers=headers,
            follow_redirects=False, deadline=deadline, callback=callback)
    except api_utils._RETRIABLE_EXCEPTIONS:
      retry = True
    else:
      retry = api_utils._should_retry(resp)

    if retry:
      retry_resp = api_utils._retry_fetch(
          url, retry_params=self.retry_params, payload=payload, method=method,
          headers=headers, follow_redirects=False, deadline=deadline)
      if retry_resp:
        resp = retry_resp
      elif not resp:
        raise

    raise ndb.Return((resp.status_code, resp.headers, resp.content))

  @ndb.tasklet
  def get_token_async(self, refresh=False):
    """Get an authentication token.

    The token is cached in memcache, keyed by the scopes argument.

    Args:
      refresh: If True, ignore a cached token; default False.

    Returns:
      An authentication token.
    """
    if self.token is not None and not refresh:
      raise ndb.Return(self.token)
    key = '%s,%s' % (self.service_account_id, ','.join(self.scopes))
    ts = None
    if not refresh:
      ts = yield _AE_TokenStorage_.get_by_id_async(
          key, use_cache=True, use_memcache=True,
          use_datastore=self.retry_params.save_access_token)
    if ts is None or ts.expires < (time.time() + 60):
      token, expires_at = yield self.make_token_async(
          self.scopes, self.service_account_id)
      timeout = int(expires_at - time.time())
      ts = _AE_TokenStorage_(id=key, token=token, expires=expires_at)
      if timeout > 0:
        yield ts.put_async(memcache_timeout=timeout,
                           use_datastore=self.retry_params.save_access_token,
                           use_cache=True, use_memcache=True)
    self.token = ts.token
    raise ndb.Return(self.token)

  def urlfetch_async(self, url, **kwds):
    """Make an async urlfetch() call.

    This just passes the url and keyword arguments to NDB's async
    urlfetch() wrapper in the current context.

    This returns a Future despite not being decorated with @ndb.tasklet!
    """
    ctx = ndb.get_context()
    return ctx.urlfetch(url, **kwds)


_RestApi = add_sync_methods(_RestApi)
