# Copyright 2012 Google Inc. All Rights Reserved.

"""Python wrappers for the Google Storage RESTful API."""





__all__ = ['ReadBuffer',
           'StreamingBuffer',
          ]

import collections
import logging
import os
import urlparse

from . import api_utils
from . import errors
from . import rest_api

try:
  from google.appengine.api import urlfetch
  from google.appengine.ext import ndb
except ImportError:
  from google.appengine.api import urlfetch
  from google.appengine.ext import ndb



class _StorageApi(rest_api._RestApi):
  """A simple wrapper for the Google Storage RESTful API.

  WARNING: Do NOT directly use this api. It's an implementation detail
  and is subject to change at any release.

  All async methods have similar args and returns.

  Args:
    path: The path to the Google Storage object or bucket, e.g.
      '/mybucket/myfile' or '/mybucket'.
    **kwd: Options for urlfetch. e.g.
      headers={'content-type': 'text/plain'}, payload='blah'.

  Returns:
    A ndb Future. When fulfilled, future.get_result() should return
    a tuple of (status, headers, content) that represents a HTTP response
    of Google Cloud Storage XML API.
  """

  api_url = 'https://storage.googleapis.com'
  read_only_scope = 'https://www.googleapis.com/auth/devstorage.read_only'
  read_write_scope = 'https://www.googleapis.com/auth/devstorage.read_write'
  full_control_scope = 'https://www.googleapis.com/auth/devstorage.full_control'

  def __getstate__(self):
    """Store state as part of serialization/pickling.

    Returns:
      A tuple (of dictionaries) with the state of this object
    """
    return (super(_StorageApi, self).__getstate__(), {'api_url': self.api_url})

  def __setstate__(self, state):
    """Restore state as part of deserialization/unpickling.

    Args:
      state: the tuple from a __getstate__ call
    """
    superstate, localstate = state
    super(_StorageApi, self).__setstate__(superstate)
    self.api_url = localstate['api_url']

  @api_utils._eager_tasklet
  @ndb.tasklet
  def do_request_async(self, url, method='GET', headers=None, payload=None,
                       deadline=None, callback=None):
    """Inherit docs.

    This method translates urlfetch exceptions to more service specific ones.
    """
    if headers is None:
      headers = {}
    if 'x-goog-api-version' not in headers:
      headers['x-goog-api-version'] = '2'
    headers['accept-encoding'] = 'gzip, *'
    try:
      resp_tuple = yield super(_StorageApi, self).do_request_async(
          url, method=method, headers=headers, payload=payload,
          deadline=deadline, callback=callback)
    except urlfetch.DownloadError, e:
      raise errors.TimeoutError(
          'Request to Google Cloud Storage timed out.', e)

    raise ndb.Return(resp_tuple)


  def post_object_async(self, path, **kwds):
    """POST to an object."""
    return self.do_request_async(self.api_url + path, 'POST', **kwds)

  def put_object_async(self, path, **kwds):
    """PUT an object."""
    return self.do_request_async(self.api_url + path, 'PUT', **kwds)

  def get_object_async(self, path, **kwds):
    """GET an object.

    Note: No payload argument is supported.
    """
    return self.do_request_async(self.api_url + path, 'GET', **kwds)

  def delete_object_async(self, path, **kwds):
    """DELETE an object.

    Note: No payload argument is supported.
    """
    return self.do_request_async(self.api_url + path, 'DELETE', **kwds)

  def head_object_async(self, path, **kwds):
    """HEAD an object.

    Depending on request headers, HEAD returns various object properties,
    e.g. Content-Length, Last-Modified, and ETag.

    Note: No payload argument is supported.
    """
    return self.do_request_async(self.api_url + path, 'HEAD', **kwds)

  def get_bucket_async(self, path, **kwds):
    """GET a bucket."""
    return self.do_request_async(self.api_url + path, 'GET', **kwds)


_StorageApi = rest_api.add_sync_methods(_StorageApi)


class ReadBuffer(object):
  """A class for reading Google storage files."""

  DEFAULT_BUFFER_SIZE = 1024 * 1024
  MAX_REQUEST_SIZE = 30 * DEFAULT_BUFFER_SIZE

  def __init__(self,
               api,
               path,
               buffer_size=DEFAULT_BUFFER_SIZE,
               max_request_size=MAX_REQUEST_SIZE):
    """Constructor.

    Args:
      api: A StorageApi instance.
      path: Path to the object, e.g. '/mybucket/myfile'.
      buffer_size: buffer size. The ReadBuffer keeps
        one buffer. But there may be a pending future that contains
        a second buffer. This size must be less than max_request_size.
      max_request_size: Max bytes to request in one urlfetch.
    """
    self._api = api
    self.name = path
    self.closed = False

    assert buffer_size <= max_request_size
    self._buffer_size = buffer_size
    self._max_request_size = max_request_size
    self._offset = 0
    self._buffer = _Buffer()
    self._etag = None

    self._request_next_buffer()

    status, headers, _ = self._api.head_object(path)
    errors.check_status(status, [200], path, resp_headers=headers)
    self._file_size = long(headers['content-length'])
    self._check_etag(headers.get('etag'))
    if self._file_size == 0:
      self._buffer_future = None

  def __getstate__(self):
    """Store state as part of serialization/pickling.

    The contents of the read buffer are not stored, only the current offset for
    data read by the client. A new read buffer is established at unpickling.
    The head information for the object (file size and etag) are stored to
    reduce startup and ensure the file has not changed.

    Returns:
      A dictionary with the state of this object
    """
    return {'api': self._api,
            'name': self.name,
            'buffer_size': self._buffer_size,
            'request_size': self._max_request_size,
            'etag': self._etag,
            'size': self._file_size,
            'offset': self._offset,
            'closed': self.closed}

  def __setstate__(self, state):
    """Restore state as part of deserialization/unpickling.

    Args:
      state: the dictionary from a __getstate__ call

    Along with restoring the state, pre-fetch the next read buffer.
    """
    self._api = state['api']
    self.name = state['name']
    self._buffer_size = state['buffer_size']
    self._max_request_size = state['request_size']
    self._etag = state['etag']
    self._file_size = state['size']
    self._offset = state['offset']
    self._buffer = _Buffer()
    self.closed = state['closed']
    self._buffer_future = None
    if self._remaining() and not self.closed:
      self._request_next_buffer()

  def __iter__(self):
    """Iterator interface.

    Note the ReadBuffer container itself is the iterator. It's
    (quote PEP0234)
    'destructive: they consumes all the values and a second iterator
    cannot easily be created that iterates independently over the same values.
    You could open the file for the second time, or seek() to the beginning.'

    Returns:
      Self.
    """
    return self

  def next(self):
    line = self.readline()
    if not line:
      raise StopIteration()
    return line

  def readline(self, size=-1):
    """Read one line delimited by '\n' from the file.

    A trailing newline character is kept in the string. It may be absent when a
    file ends with an incomplete line. If the size argument is non-negative,
    it specifies the maximum string size (counting the newline) to return.
    A negative size is the same as unspecified. Empty string is returned
    only when EOF is encountered immediately.

    Args:
      size: Maximum number of bytes to read. If not specified, readline stops
        only on '\n' or EOF.

    Returns:
      The data read as a string.

    Raises:
      IOError: When this buffer is closed.
    """
    self._check_open()
    if size == 0 or not self._remaining():
      return ''

    data_list = []
    newline_offset = self._buffer.find_newline(size)
    while newline_offset < 0:
      data = self._buffer.read(size)
      size -= len(data)
      self._offset += len(data)
      data_list.append(data)
      if size == 0 or not self._remaining():
        return ''.join(data_list)
      self._buffer.reset(self._buffer_future.get_result())
      self._request_next_buffer()
      newline_offset = self._buffer.find_newline(size)

    data = self._buffer.read_to_offset(newline_offset + 1)
    self._offset += len(data)
    data_list.append(data)

    return ''.join(data_list)

  def read(self, size=-1):
    """Read data from RAW file.

    Args:
      size: Number of bytes to read as integer. Actual number of bytes
        read is always equal to size unless EOF is reached. If size is
        negative or unspecified, read the entire file.

    Returns:
      data read as str.

    Raises:
      IOError: When this buffer is closed.
    """
    self._check_open()
    if not self._remaining():
      return ''

    data_list = []
    while True:
      remaining = self._buffer.remaining()
      if size >= 0 and size < remaining:
        data_list.append(self._buffer.read(size))
        self._offset += size
        break
      else:
        size -= remaining
        self._offset += remaining
        data_list.append(self._buffer.read())

        if self._buffer_future is None:
          if size < 0 or size >= self._remaining():
            needs = self._remaining()
          else:
            needs = size
          data_list.extend(self._get_segments(self._offset, needs))
          self._offset += needs
          break

        if self._buffer_future:
          self._buffer.reset(self._buffer_future.get_result())
          self._buffer_future = None

    if self._buffer_future is None:
      self._request_next_buffer()
    return ''.join(data_list)

  def _remaining(self):
    return self._file_size - self._offset

  def _request_next_buffer(self):
    """Request next buffer.

    Requires self._offset and self._buffer are in consistent state
    """
    self._buffer_future = None
    next_offset = self._offset + self._buffer.remaining()
    if not hasattr(self, '_file_size') or next_offset != self._file_size:
      self._buffer_future = self._get_segment(next_offset,
                                              self._buffer_size)

  def _get_segments(self, start, request_size):
    """Get segments of the file from Google Storage as a list.

    A large request is broken into segments to avoid hitting urlfetch
    response size limit. Each segment is returned from a separate urlfetch.

    Args:
      start: start offset to request. Inclusive. Have to be within the
        range of the file.
      request_size: number of bytes to request.

    Returns:
      A list of file segments in order
    """
    if not request_size:
      return []

    end = start + request_size
    futures = []

    while request_size > self._max_request_size:
      futures.append(self._get_segment(start, self._max_request_size))
      request_size -= self._max_request_size
      start += self._max_request_size
    if start < end:
      futures.append(self._get_segment(start, end-start))
    return [fut.get_result() for fut in futures]

  @ndb.tasklet
  def _get_segment(self, start, request_size):
    """Get a segment of the file from Google Storage.

    Args:
      start: start offset of the segment. Inclusive. Have to be within the
        range of the file.
      request_size: number of bytes to request. Have to be small enough
        for a single urlfetch request. May go over the logical range of the
        file.

    Yields:
      a segment [start, start + request_size) of the file.

    Raises:
      ValueError: if the file has changed while reading.
    """
    end = start + request_size - 1
    content_range = '%d-%d' % (start, end)
    headers = {'Range': 'bytes=' + content_range}
    status, resp_headers, content = yield self._api.get_object_async(
        self.name, headers=headers)
    errors.check_status(status, [200, 206], self.name, headers, resp_headers)
    self._check_etag(resp_headers.get('etag'))
    raise ndb.Return(content)

  def _check_etag(self, etag):
    """Check if etag is the same across requests to GCS.

    If self._etag is None, set it. If etag is set, check that the new
    etag equals the old one.

    In the __init__ method, we fire one HEAD and one GET request using
    ndb tasklet. One of them would return first and set the first value.

    Args:
      etag: etag from a GCS HTTP response. None if etag is not part of the
        response header. It could be None for example in the case of GCS
        composite file.

    Raises:
      ValueError: if two etags are not equal.
    """
    if etag is None:
      return
    elif self._etag is None:
      self._etag = etag
    elif self._etag != etag:
      raise ValueError('File on GCS has changed while reading.')

  def close(self):
    self.closed = True
    self._buffer = None
    self._buffer_future = None

  def __enter__(self):
    return self

  def __exit__(self, atype, value, traceback):
    self.close()
    return False

  def seek(self, offset, whence=os.SEEK_SET):
    """Set the file's current offset.

    Note if the new offset is out of bound, it is adjusted to either 0 or EOF.

    Args:
      offset: seek offset as number.
      whence: seek mode. Supported modes are os.SEEK_SET (absolute seek),
        os.SEEK_CUR (seek relative to the current position), and os.SEEK_END
        (seek relative to the end, offset should be negative).

    Raises:
      IOError: When this buffer is closed.
      ValueError: When whence is invalid.
    """
    self._check_open()

    self._buffer.reset()
    self._buffer_future = None

    if whence == os.SEEK_SET:
      self._offset = offset
    elif whence == os.SEEK_CUR:
      self._offset += offset
    elif whence == os.SEEK_END:
      self._offset = self._file_size + offset
    else:
      raise ValueError('Whence mode %s is invalid.' % str(whence))

    self._offset = min(self._offset, self._file_size)
    self._offset = max(self._offset, 0)
    if self._remaining():
      self._request_next_buffer()

  def tell(self):
    """Tell the file's current offset.

    Returns:
      current offset in reading this file.

    Raises:
      IOError: When this buffer is closed.
    """
    self._check_open()
    return self._offset

  def _check_open(self):
    if self.closed:
      raise IOError('Buffer is closed.')

  def seekable(self):
    return True

  def readable(self):
    return True

  def writable(self):
    return False


class _Buffer(object):
  """In memory buffer."""

  def __init__(self):
    self.reset()

  def reset(self, content='', offset=0):
    self._buffer = content
    self._offset = offset

  def read(self, size=-1):
    """Returns bytes from self._buffer and update related offsets.

    Args:
      size: number of bytes to read starting from current offset.
        Read the entire buffer if negative.

    Returns:
      Requested bytes from buffer.
    """
    if size < 0:
      offset = len(self._buffer)
    else:
      offset = self._offset + size
    return self.read_to_offset(offset)

  def read_to_offset(self, offset):
    """Returns bytes from self._buffer and update related offsets.

    Args:
      offset: read from current offset to this offset, exclusive.

    Returns:
      Requested bytes from buffer.
    """
    assert offset >= self._offset
    result = self._buffer[self._offset: offset]
    self._offset += len(result)
    return result

  def remaining(self):
    return len(self._buffer) - self._offset

  def find_newline(self, size=-1):
    """Search for newline char in buffer starting from current offset.

    Args:
      size: number of bytes to search. -1 means all.

    Returns:
      offset of newline char in buffer. -1 if doesn't exist.
    """
    if size < 0:
      return self._buffer.find('\n', self._offset)
    return self._buffer.find('\n', self._offset, self._offset + size)


class StreamingBuffer(object):
  """A class for creating large objects using the 'resumable' API.

  The API is a subset of the Python writable stream API sufficient to
  support writing zip files using the zipfile module.

  The exact sequence of calls and use of headers is documented at
  https://developers.google.com/storage/docs/developer-guide#unknownresumables
  """

  _blocksize = 256 * 1024

  _maxrequestsize = 16 * _blocksize

  def __init__(self,
               api,
               path,
               content_type=None,
               gcs_headers=None):
    """Constructor.

    Args:
      api: A StorageApi instance.
      path: Path to the object, e.g. '/mybucket/myfile'.
      content_type: Optional content-type; Default value is
        delegate to Google Cloud Storage.
      gcs_headers: additional gs headers as a str->str dict, e.g
        {'x-goog-acl': 'private', 'x-goog-meta-foo': 'foo'}.
    """
    assert self._maxrequestsize > self._blocksize
    assert self._maxrequestsize % self._blocksize == 0

    self._api = api
    self.name = path
    self.closed = False

    self._buffer = collections.deque()
    self._buffered = 0
    self._written = 0
    self._offset = 0

    headers = {'x-goog-resumable': 'start'}
    if content_type:
      headers['content-type'] = content_type
    if gcs_headers:
      headers.update(gcs_headers)
    status, resp_headers, _ = self._api.post_object(path, headers=headers)
    errors.check_status(status, [201], path, headers, resp_headers)
    loc = resp_headers.get('location')
    if not loc:
      raise IOError('No location header found in 201 response')
    parsed = urlparse.urlparse(loc)
    self._path_with_token = '%s?%s' % (self.name, parsed.query)

  def __getstate__(self):
    """Store state as part of serialization/pickling.

    The contents of the write buffer are stored. Writes to the underlying
    storage are required to be on block boundaries (_blocksize) except for the
    last write. In the worst case the pickled version of this object may be
    slightly larger than the blocksize.

    Returns:
      A dictionary with the state of this object

    """
    return {'api': self._api,
            'name': self.name,
            'path_token': self._path_with_token,
            'buffer': self._buffer,
            'buffered': self._buffered,
            'written': self._written,
            'offset': self._offset,
            'closed': self.closed}

  def __setstate__(self, state):
    """Restore state as part of deserialization/unpickling.

    Args:
      state: the dictionary from a __getstate__ call
    """
    self._api = state['api']
    self._path_with_token = state['path_token']
    self._buffer = state['buffer']
    self._buffered = state['buffered']
    self._written = state['written']
    self._offset = state['offset']
    self.closed = state['closed']
    self.name = state['name']

  def write(self, data):
    """Write some bytes.

    Args:
      data: data to write. str.

    Raises:
      TypeError: if data is not of type str.
    """
    self._check_open()
    if not isinstance(data, str):
      raise TypeError('Expected str but got %s.' % type(data))
    if not data:
      return
    self._buffer.append(data)
    self._buffered += len(data)
    self._offset += len(data)
    if self._buffered >= self._blocksize:
      self._flush()

  def flush(self):
    """Dummy API.

    This API is provided because the zipfile module uses it.  It is a
    no-op because Google Storage *requires* that all writes except for
    the final one are multiples on 256K bytes aligned on 256K-byte
    boundaries.
    """
    self._check_open()

  def tell(self):
    """Return the total number of bytes passed to write() so far.

    (There is no seek() method.)
    """
    self._check_open()
    return self._offset

  def close(self):
    """Flush the buffer and finalize the file.

    When this returns the new file is available for reading.
    """
    if not self.closed:
      self.closed = True
      self._flush(finish=True)
      self._buffer = None

  def __enter__(self):
    return self

  def __exit__(self, atype, value, traceback):
    self.close()
    return False

  def _flush(self, finish=False):
    """Internal API to flush.

    This is called only when the total amount of buffered data is at
    least self._blocksize, or to flush the final (incomplete) block of
    the file with finish=True.
    """
    flush_len = 0 if finish else self._blocksize
    last = False

    while self._buffered >= flush_len:
      buffer = []
      buffered = 0

      while self._buffer:
        buf = self._buffer.popleft()
        size = len(buf)
        self._buffered -= size
        buffer.append(buf)
        buffered += size
        if buffered >= self._maxrequestsize:
          break

      if buffered > self._maxrequestsize:
        excess = buffered - self._maxrequestsize
      elif finish:
        excess = 0
      else:
        excess = buffered % self._blocksize

      if excess:
        over = buffer.pop()
        size = len(over)
        assert size >= excess
        buffered -= size
        head, tail = over[:-excess], over[-excess:]
        self._buffer.appendleft(tail)
        self._buffered += len(tail)
        if head:
          buffer.append(head)
          buffered += len(head)

      if finish:
        last = not self._buffered
      self._send_data(''.join(buffer), last)
      if last:
        break

  def _send_data(self, data, last):
    """Send the block to the storage service and update self._written."""
    headers = {}
    length = self._written + len(data)

    if data:
      headers['content-range'] = ('bytes %d-%d/%s' %
                                  (self._written, length-1,
                                   length if last else '*'))
    else:
      headers['content-range'] = ('bytes */%s' %
                                  length if last else '*')
    status, response_headers, _ = self._api.put_object(
        self._path_with_token, payload=data, headers=headers)
    if last:
      expected = 200
    else:
      expected = 308
    if expected == 308 and status == 200:
      logging.warning(
          'This upload session for file %s has already been finalized. It is '
          'likely this is an outdated copy of an already closed file handler.'
          'Request headers: %r.\n'
          'Response headers: %r.\n', self.name, headers, response_headers)
    else:
      errors.check_status(status, [expected], self.name, headers,
                          response_headers,
                          {'upload_path': self._path_with_token})
    self._written += len(data)

  def _check_open(self):
    if self.closed:
      raise IOError('Buffer is closed.')

  def seekable(self):
    return False

  def readable(self):
    return False

  def writable(self):
    return True
