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
import re
import json
import sys

import webapp2
from google.appengine.ext.webapp import template
from google.appengine.api import images
from google.appengine.api import urlfetch

sys.path.append(os.path.join(os.path.dirname(__file__),'../lib'))
from bs4 import BeautifulSoup
import cloudstorage as gcs

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


def maybe_process_image(image_url, base_name):
  if CLOUD_STORAGE_ROOT_URL in image_url:
    return (image_url, None)

  image_result = urlfetch.fetch(image_url)
  if image_result.status_code < 200 or image_result.status_code >= 300:
    raise IOError('Error downloading image: HTTP %d.' % image_result.status_code)

  base_filename = re.sub(r'[^\w]+', '-', base_name.strip().lower())

  # main image
  image_gcs_path = '/muzeifeaturedart/' + base_filename + '.jpg'
  # resize to max width 4000 or max height 2000
  image_contents = image_result.content
  image = images.Image(image_contents)
  if image.height > 2000:
    image.resize(width=(thumb.width * 2000 / thumb.height), height=2000)
    image_contents = image.execute_transforms(output_encoding=images.JPEG, quality=80)
  elif image.width > 4000:
    image.resize(width=4000, height=(thumb.height * 4000 / thumb.thumb.width))
    image_contents = image.execute_transforms(output_encoding=images.JPEG, quality=80)
  # upload with default ACLs set on the bucket  # or use options={'x-goog-acl': 'public-read'})
  gcs_file = gcs.open(image_gcs_path, 'w', content_type='image/jpeg')
  gcs_file.write(image_contents)
  gcs_file.close()

  # thumb
  thumb_gcs_path = '/muzeifeaturedart/' + base_filename + '_thumb.jpg'
  thumb = images.Image(image_result.content)
  thumb.resize(width=(thumb.width * 600 / thumb.height), height=600)
  thumb_contents = thumb.execute_transforms(output_encoding=images.JPEG, quality=40)
  gcs_file = gcs.open(thumb_gcs_path, 'w', content_type='image/jpeg')
  gcs_file.write(thumb_contents)
  gcs_file.close()

  return (CLOUD_STORAGE_ROOT_URL + image_gcs_path,
          CLOUD_STORAGE_ROOT_URL + thumb_gcs_path)


CLOUD_STORAGE_ROOT_URL = 'http://storage.googleapis.com'


class ServiceAddHandler(BaseHandler):
  def post(self):
    artwork_json = json.loads(self.request.get('json'))
    new_image_url, new_thumb_url = maybe_process_image(
        artwork_json['imageUri'],
        artwork_json['title'] + ' ' + artwork_json['byline'])
    if not new_thumb_url and 'thumbUri' in artwork_json:
      new_thumb_url = artwork_json['thumbUri']
    new_artwork = FeaturedArtwork(
        title=artwork_json['title'],
        byline=artwork_json['byline'],
        image_url=new_image_url,
        thumb_url=new_thumb_url,
        details_url=artwork_json['detailsUri'],
        publish_date=datetime.datetime
            .utcfromtimestamp(artwork_json['publishDate'] / 1000)
            .date())
    new_artwork.save()
    self.response.set_status(200)


class ServiceAddFromWikiPaintingsHandler(BaseHandler):
  def post(self):
    wikipaintings_url = self.request.get('wikiPaintingsUrl')
    result = urlfetch.fetch(wikipaintings_url)
    if result.status_code < 200 or result.status_code >= 300:
      self.response.out.write('Error processing URL: HTTP %d. Content: %s'
          % (result.status_code, result.content))
      self.response.set_status(500)
      return

    self.process_html(wikipaintings_url, result.content)


  def process_html(self, url, html):
    soup = BeautifulSoup(html)

    details_url = re.sub(r'#.+', '', url, re.I | re.S) + '?utm_source=Muzei&utm_campaign=Muzei'
    title = soup.find(itemprop='name').get_text()
    author = soup.find(itemprop='author').get_text()
    completion_year_el = soup.find(itemprop='dateCreated')
    byline = author + ((', ' + completion_year_el.get_text()) if completion_year_el else '')
    image_url = soup.find(id='paintingImage')['href']

    if not title or not author or not image_url:
      self.response.out.write('Could not parse HTML')
      self.response.set_status(500)
      return

    image_url, thumb_url = maybe_process_image(image_url, title + ' ' + byline)

    # create the artwork entry
    new_artwork = FeaturedArtwork(
        title=title,
        byline=byline,
        image_url=image_url,
        thumb_url=thumb_url,
        details_url=details_url,
        publish_date=datetime.datetime
            .utcfromtimestamp(int(self.request.get('publishDate')) / 1000)
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

    new_image_url, new_thumb_url = maybe_process_image(
        artwork_json['imageUri'],
        artwork_json['title'] + ' ' + artwork_json['byline'])
    if not new_thumb_url and 'thumbUri' in artwork_json:
      new_thumb_url = artwork_json['thumbUri']

    target_artwork.image_url = new_image_url
    target_artwork.thumb_url = new_thumb_url
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
            title='Schedule',
            )))


app = webapp2.WSGIApplication([
    ('/backroom/s/list', ServiceListHandler),
    ('/backroom/s/add', ServiceAddHandler),
    ('/backroom/s/addfromwikipaintings', ServiceAddFromWikiPaintingsHandler),
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