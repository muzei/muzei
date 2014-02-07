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

from models import FeaturedArtwork

IS_DEVELOPMENT = ('Development' in os.environ['SERVER_SOFTWARE'])


def values_with_defaults(values):
  v = dict()
  v.update(values)
  return v


#http://stackoverflow.com/questions/8777753#8778548
def date_to_timestamp(dt, epoch=datetime.date(1970,1,1)):
  td = dt - epoch
  return td.days * 24 * 3600


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


def make_static_page_handler(template_file, page_title):
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


class ServiceListHandler(BaseHandler):
  def get(self):
    self.response.headers['Content-Type'] = 'application/json'
    self.response.out.write(self.render())

  def render(self):
    queue = (FeaturedArtwork.all()
        .filter('publish_date >=', datetime.date.today() - datetime.timedelta(days=30))
        .order('publish_date')
        .fetch(1000))
    return json.dumps([dict(
        id=a.key().id(),
        title=a.title,
        byline=a.byline,
        imageUri=a.image_url,
        thumbUri=a.thumb_url,
        detailsUri=a.details_url,
        publishDate=date_to_timestamp(a.publish_date),)
        for a in queue])


class ServiceAddHandler(BaseHandler):
  def post(self):
    artwork_json = json.loads(self.request.get('json'))
    new_artwork = FeaturedArtwork(
        title=artwork_json['title'],
        byline=artwork_json['byline'],
        image_url=artwork_json['imageUri'],
        thumb_url=(artwork_json['thumbUri']
                   if 'thumbUri' in artwork_json
                   else (artwork_json['imageUri'] + '!BlogSmall.jpg')),
        details_url=artwork_json['detailsUri'],
        publish_date=datetime.datetime
            .utcfromtimestamp(artwork_json['publishDate'] / 1000)
            .date())
    new_artwork.save()
    self.response.set_status(200)


class ServiceEditHandler(BaseHandler):
  def post(self):
    id = long(self.request.get('id'))
    artwork_json = json.loads(self.request.get('json'))
    target_artwork = FeaturedArtwork.get_by_id(id)
    if not target_artwork:
      self.response.set_status(404)
      return

    target_artwork.title = artwork_json['title']
    target_artwork.byline = artwork_json['byline']
    target_artwork.image_url = artwork_json['imageUri']
    target_artwork.thumb_url = (artwork_json['thumbUri']
               if 'thumbUri' in artwork_json
               else (artwork_json['imageUri'] + '!BlogSmall.jpg'))
    target_artwork.details_url = artwork_json['detailsUri']
    target_artwork.save()
    self.response.set_status(200)


class ServiceMoveHandler(BaseHandler):
  def post(self):
    id = long(self.request.get('id'))
    publish_date = (datetime.datetime
        .utcfromtimestamp(long(self.request.get('publishDate')) / 1000)
        .date())
    target_artwork = FeaturedArtwork.get_by_id(id)
    if not target_artwork:
      self.response.set_status(404)
      return

    # shift other artworks over
    self.move_artwork(target_artwork, publish_date, target_artwork.key().id())
    self.response.set_status(200)

  def move_artwork(self, artwork, publish_date, initial_artwork_id):
    # cascade moves
    current_artwork_at_date = FeaturedArtwork.all().filter('publish_date =', publish_date).get()
    if current_artwork_at_date and current_artwork_at_date.key().id() != initial_artwork_id:
      self.move_artwork(current_artwork_at_date, publish_date + datetime.timedelta(hours=24),
          initial_artwork_id)
    artwork.publish_date = publish_date
    artwork.save()


class ServiceRemoveHandler(BaseHandler):
  def post(self):
    id = long(self.request.get('id'))
    target_artwork = FeaturedArtwork.get_by_id(id)
    if not target_artwork:
      self.response.set_status(404)
      return
    target_artwork.delete()
    self.response.set_status(200)


class ScheduleHandler(BaseHandler):
  def get(self):
    self.response.out.write(self.render())

  def render(self):
    return template.render(
        os.path.join(os.path.dirname(__file__), '../templates/backroom_schedule.html'),
        values_with_defaults(dict(
            title='Cocktails',
            )))


app = webapp2.WSGIApplication([
    ('/backroom/s/list', ServiceListHandler),
    ('/backroom/s/add', ServiceAddHandler),
    ('/backroom/s/edit', ServiceEditHandler),
    ('/backroom/s/remove', ServiceRemoveHandler),
    ('/backroom/s/move', ServiceMoveHandler),
    ('/backroom/schedule', ScheduleHandler),
    ],
    debug=IS_DEVELOPMENT)


def main():
  app.run()


if __name__ == '__main__':
  main()