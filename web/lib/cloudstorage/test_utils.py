# Copyright 2013 Google Inc. All Rights Reserved.

"""Utils for testing."""


class MockUrlFetchResult(object):

  def __init__(self, status, headers, body):
    self.status_code = status
    self.headers = headers
    self.content = body
    self.content_was_truncated = False
    self.final_url = None
