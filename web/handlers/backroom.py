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
import os
import json

import webapp2
from google.appengine.ext.webapp import template

from handlers import addfromwikipaintings
from handlers.common import *
from models import FeaturedArtwork


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
        thumb_url=(artwork_json['thumbUri'] if 'thumbUri' in artwork_json else None),
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
    ('/backroom/s/addfromwikipaintings', addfromwikipaintings.ServiceAddFromWikiPaintingsHandler),
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