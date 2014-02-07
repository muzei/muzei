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

import datetime
import logging
import os
import json

import webapp2
from google.appengine.ext.webapp import template
from google.appengine.api import memcache

from models import FeaturedArtwork, _serialize_datetime

IS_DEVELOPMENT = ('Development' in os.environ['SERVER_SOFTWARE'])

START_TIME = datetime.time(1, 55, 0, tzinfo=None) # 1:55am UTC
NEXT_PADDING = datetime.timedelta(minutes=5) # Next one should be requested at 1:55am + 5 minutes


def values_with_defaults(values):
  v = dict()
  v.update(values)
  return v


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


def make_static_page_handler(template_file, page_title=None):
  class StaticHandler(BaseHandler):
    def get(self):
      self.response.out.write(self.render(template_file))

    @pagecache('static_page')
    def render(self, template_file):
      return template.render(
          os.path.join(os.path.dirname(__file__), '../templates/' + template_file),
          values_with_defaults(dict(title=page_title)))
  
  return StaticHandler


def make_redirect_handler(url_template):
  class RedirectHandler(BaseHandler):
    def get(self, *args):
      url = url_template
      for i, arg in enumerate(args):
        url = url.replace("$" + str(i + 1), arg)
      self.redirect(url)
  
  return RedirectHandler


class FeaturedArtworkHandler(BaseHandler):
  def get(self):
    self.response.headers['Content-Type'] = 'application/json'
    self.response.out.write(self.render(self.request.get('callback', '')))

  @pagecache('featured_artwork')
  def render(self, callback):
    now = datetime.datetime.utcnow()
    current = None

    # Get up to 5 artworks published earlier than 2 days from now, ordered by latest first
    latest_artworks = (FeaturedArtwork.all()
        .filter('publish_date <=', datetime.date.today() + datetime.timedelta(days=2))
        .order('-publish_date')
        .fetch(5))

    # Pick out the first artwork in that set that has actually been published
    for artwork in latest_artworks:
      if now >= datetime.datetime.combine(artwork.publish_date, START_TIME):
        current = artwork
        break

    ret_obj = dict()
    if current is not None:
      featured = dict(
          title=current.title,
          byline=current.byline,
          imageUri=current.image_url,
          detailsUri=current.details_url)
      if current.thumb_url:
        featured['thumbUri'] = current.thumb_url

      # The next update time is at START_TIME tomorrow
      next_time = datetime.datetime.combine(datetime.date.today() \
          + datetime.timedelta(days=1), START_TIME) + NEXT_PADDING
      featured['nextTime'] = _serialize_datetime(next_time)

      cache_expire_time = next_time - datetime.timedelta(minutes=5)
      expire_seconds = max(0, (cache_expire_time - now).total_seconds())
      self.response.headers['Cache-Control'] = 'max-age=%d, must-revalidate, public' % expire_seconds
      self.response.headers['Expires'] = cache_expire_time.strftime('%a, %d %b %Y %H:%M:%S GMT')

      ret_obj = featured

    s = json.dumps(ret_obj, sort_keys=True)
    if callback:
      return '%s(%s)' % (callback, s)
    else:
      return s


app = webapp2.WSGIApplication([
    ('/', make_static_page_handler('landing.html')),
    ('/featured', FeaturedArtworkHandler),
    ],
    debug=IS_DEVELOPMENT)


def main():
  app.run()


if __name__ == '__main__':
  main()