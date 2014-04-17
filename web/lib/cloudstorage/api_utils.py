# Copyright 2013 Google Inc. All Rights Reserved.

"""Util functions and classes for cloudstorage_api."""



__all__ = ['set_default_retry_params',
           'RetryParams',
          ]

import copy
import httplib
import logging
import math
import os
import threading
import time
import urllib


try:
  from google.appengine.api import urlfetch
  from google.appengine.datastore import datastore_rpc
  from google.appengine.ext.ndb import eventloop
  from google.appengine.ext.ndb import utils
  from google.appengine import runtime
  from google.appengine.runtime import apiproxy_errors
except ImportError:
  from google.appengine.api import urlfetch
  from google.appengine.datastore import datastore_rpc
  from google.appengine import runtime
  from google.appengine.runtime import apiproxy_errors
  from google.appengine.ext.ndb import eventloop
  from google.appengine.ext.ndb import utils


_RETRIABLE_EXCEPTIONS = (urlfetch.DownloadError,
                         apiproxy_errors.Error)

_thread_local_settings = threading.local()
_thread_local_settings.default_retry_params = None


def set_default_retry_params(retry_params):
  """Set a default RetryParams for current thread current request."""
  _thread_local_settings.default_retry_params = copy.copy(retry_params)


def _get_default_retry_params():
  """Get default RetryParams for current request and current thread.

  Returns:
    A new instance of the default RetryParams.
  """
  default = getattr(_thread_local_settings, 'default_retry_params', None)
  if default is None or not default.belong_to_current_request():
    return RetryParams()
  else:
    return copy.copy(default)


def _quote_filename(filename):
  """Quotes filename to use as a valid URI path.

  Args:
    filename: user provided filename. /bucket/filename.

  Returns:
    The filename properly quoted to use as URI's path component.
  """
  return urllib.quote(filename)


def _should_retry(resp):
  """Given a urlfetch response, decide whether to retry that request."""
  return (resp.status_code == httplib.REQUEST_TIMEOUT or
          (resp.status_code >= 500 and
           resp.status_code < 600))


class RetryParams(object):
  """Retry configuration parameters."""

  @datastore_rpc._positional(1)
  def __init__(self,
               backoff_factor=2.0,
               initial_delay=0.1,
               max_delay=10.0,
               min_retries=2,
               max_retries=5,
               max_retry_period=30.0,
               urlfetch_timeout=None,
               save_access_token=False):
    """Init.

    This object is unique per request per thread.

    Library will retry according to this setting when App Engine Server
    can't call urlfetch, urlfetch timed out, or urlfetch got a 408 or
    500-600 response.

    Args:
      backoff_factor: exponential backoff multiplier.
      initial_delay: seconds to delay for the first retry.
      max_delay: max seconds to delay for every retry.
      min_retries: min number of times to retry. This value is automatically
        capped by max_retries.
      max_retries: max number of times to retry. Set this to 0 for no retry.
      max_retry_period: max total seconds spent on retry. Retry stops when
        this period passed AND min_retries has been attempted.
      urlfetch_timeout: timeout for urlfetch in seconds. Could be None,
        in which case the value will be chosen by urlfetch module.
      save_access_token: persist access token to datastore to avoid
        excessive usage of GetAccessToken API. Usually the token is cached
        in process and in memcache. In some cases, memcache isn't very
        reliable.
    """
    self.backoff_factor = self._check('backoff_factor', backoff_factor)
    self.initial_delay = self._check('initial_delay', initial_delay)
    self.max_delay = self._check('max_delay', max_delay)
    self.max_retry_period = self._check('max_retry_period', max_retry_period)
    self.max_retries = self._check('max_retries', max_retries, True, int)
    self.min_retries = self._check('min_retries', min_retries, True, int)
    if self.min_retries > self.max_retries:
      self.min_retries = self.max_retries

    self.urlfetch_timeout = None
    if urlfetch_timeout is not None:
      self.urlfetch_timeout = self._check('urlfetch_timeout', urlfetch_timeout)
    self.save_access_token = self._check('save_access_token', save_access_token,
                                         True, bool)

    self._request_id = os.getenv('REQUEST_LOG_ID')

  def __eq__(self, other):
    if not isinstance(other, self.__class__):
      return False
    return self.__dict__ == other.__dict__

  def __ne__(self, other):
    return not self.__eq__(other)

  @classmethod
  def _check(cls, name, val, can_be_zero=False, val_type=float):
    """Check init arguments.

    Args:
      name: name of the argument. For logging purpose.
      val: value. Value has to be non negative number.
      can_be_zero: whether value can be zero.
      val_type: Python type of the value.

    Returns:
      The value.

    Raises:
      ValueError: when invalid value is passed in.
      TypeError: when invalid value type is passed in.
    """
    valid_types = [val_type]
    if val_type is float:
      valid_types.append(int)

    if type(val) not in valid_types:
      raise TypeError(
          'Expect type %s for parameter %s' % (val_type.__name__, name))
    if val < 0:
      raise ValueError(
          'Value for parameter %s has to be greater than 0' % name)
    if not can_be_zero and val == 0:
      raise ValueError(
          'Value for parameter %s can not be 0' % name)
    return val

  def belong_to_current_request(self):
    return os.getenv('REQUEST_LOG_ID') == self._request_id

  def delay(self, n, start_time):
    """Calculate delay before the next retry.

    Args:
      n: the number of current attempt. The first attempt should be 1.
      start_time: the time when retry started in unix time.

    Returns:
      Number of seconds to wait before next retry. -1 if retry should give up.
    """
    if (n > self.max_retries or
        (n > self.min_retries and
         time.time() - start_time > self.max_retry_period)):
      return -1
    return min(
        math.pow(self.backoff_factor, n-1) * self.initial_delay,
        self.max_delay)


def _retry_fetch(url, retry_params, **kwds):
  """A blocking fetch function similar to urlfetch.fetch.

  This function should be used when a urlfetch has timed out or the response
  shows http request timeout. This function will put current thread to
  sleep between retry backoffs.

  Args:
    url: url to fetch.
    retry_params: an instance of RetryParams.
    **kwds: keyword arguments for urlfetch. If deadline is specified in kwds,
      it precedes the one in RetryParams. If none is specified, it's up to
      urlfetch to use its own default.

  Returns:
    A urlfetch response from the last retry. None if no retry was attempted.

  Raises:
    Whatever exception encountered during the last retry.
  """
  n = 1
  start_time = time.time()
  delay = retry_params.delay(n, start_time)
  if delay <= 0:
    return

  logging.info('Will retry request to %s.', url)
  while delay > 0:
    resp = None
    try:
      logging.info('Retry in %s seconds.', delay)
      time.sleep(delay)
      resp = urlfetch.fetch(url, **kwds)
    except runtime.DeadlineExceededError:
      logging.info(
          'Urlfetch retry %s will exceed request deadline '
          'after %s seconds total', n, time.time() - start_time)
      raise
    except _RETRIABLE_EXCEPTIONS, e:
      pass

    n += 1
    delay = retry_params.delay(n, start_time)
    if resp and not _should_retry(resp):
      break
    elif resp:
      logging.info(
          'Got status %s from GCS.', resp.status_code)
    else:
      logging.info(
          'Got exception "%r" while contacting GCS.', e)

  if resp:
    return resp

  logging.info('Urlfetch failed after %s retries and %s seconds in total.',
               n - 1, time.time() - start_time)
  raise


def _run_until_rpc():
  """Eagerly evaluate tasklets until it is blocking on some RPC.

  Usually ndb eventloop el isn't run until some code calls future.get_result().

  When an async tasklet is called, the tasklet wrapper evaluates the tasklet
  code into a generator, enqueues a callback _help_tasklet_along onto
  the el.current queue, and returns a future.

  _help_tasklet_along, when called by the el, will
  get one yielded value from the generator. If the value if another future,
  set up a callback _on_future_complete to invoke _help_tasklet_along
  when the dependent future fulfills. If the value if a RPC, set up a
  callback _on_rpc_complete to invoke _help_tasklet_along when the RPC fulfills.
  Thus _help_tasklet_along drills down
  the the chain of futures until some future is blocked by RPC. El runs
  all callbacks and constantly check pending RPC status.
  """
  el = eventloop.get_event_loop()
  while el.current:
    el.run0()


def _eager_tasklet(tasklet):
  """Decorator to turn tasklet to run eagerly."""

  @utils.wrapping(tasklet)
  def eager_wrapper(*args, **kwds):
    fut = tasklet(*args, **kwds)
    _run_until_rpc()
    return fut

  return eager_wrapper
