# Copyright 2014 Google Inc.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

from datetime import date
import logging
import os

import webapp2
from google.appengine.api import memcache
from google.appengine.ext.webapp import template


IS_DEVELOPMENT = ('Development' in os.environ['SERVER_SOFTWARE'])


#http://stackoverflow.com/questions/8777753#8778548
def date_to_timestamp(dt, epoch=date(1970,1,1)):
  td = dt - epoch
  return td.days * 24 * 3600


def values_with_defaults(values):
  v = dict()
  v.update(values)
  return v


def make_static_page_handler(template_file):
  class StaticHandler(BaseHandler):
    def get(self):
      self.response.out.write(self.render(template_file))

    @pagecache('static_page')
    def render(self, template_file):
      return template.render(
          os.path.join(os.path.dirname(__file__), '../templates/' + template_file),
          values_with_defaults(dict()))
  
  return StaticHandler


def make_redirect_handler(url_template):
  class RedirectHandler(BaseHandler):
    def get(self, *args):
      url = url_template
      for i, arg in enumerate(args):
        url = url.replace("$" + str(i + 1), arg)
      self.redirect(url)
  
  return RedirectHandler


class pagecache:
  def __init__(self, key):
    self.key = key

  def __call__(self, fn):
    def _new_fn(fnSelf, *args):
      cache_key = self.key + '_'.join([str(arg) for arg in args])
      return_value = (memcache.get(cache_key)
                      if not 'Development' in os.environ['SERVER_SOFTWARE']
                      else None)
      if return_value and not IS_DEVELOPMENT:
        return return_value
      else:
        return_value = fn(fnSelf, *args)
        memcache.set(cache_key, return_value, 60)  # cache for a minute
        return return_value

    return _new_fn


class BaseHandler(webapp2.RequestHandler):
  def handle_exception(self, exception, debug):
    # Log the error.
    logging.exception(exception)

    # Set a custom message.
    self.response.write('An error occurred.')

    # If the exception is a HTTPException, use its error code.
    # Otherwise use a generic 500 error code.
    if isinstance(exception, webapp2.HTTPException):
      self.response.set_status(exception.code)
    else:
      self.response.set_status(500)
