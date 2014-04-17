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
import sys

from google.appengine.api import images
from google.appengine.api import urlfetch

sys.path.append(os.path.join(os.path.dirname(__file__),'../lib'))
from bs4 import BeautifulSoup
import cloudstorage as gcs

from handlers.common import *
from models import FeaturedArtwork


CLOUD_STORAGE_ROOT_URL = 'http://storage.googleapis.com'


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
    #image_url = soup.find(itemprop='image')['src']

    if not title or not author or not image_url:
      self.response.out.write('Could not parse HTML')
      self.response.set_status(500)
      return

    # download the image
    image_result = urlfetch.fetch(image_url)
    if image_result.status_code < 200 or image_result.status_code >= 300:
      self.response.out.write('Error downloading image: HTTP %d.' % image_result.status_code)
      self.response.set_status(500)
      return

    base_filename = (title + ' ' + byline).lower()
    base_filename = re.sub(r'[^\w]+', '-', base_filename)

    # main image
    image_gcs_path = '/muzeifeaturedart/' + base_filename + '.jpg'
    # upload with default ACLs set on the bucket  # or use options={'x-goog-acl': 'public-read'})
    gcs_file = gcs.open(image_gcs_path, 'w', content_type='image/jpeg')
    gcs_file.write(image_result.content)
    gcs_file.close()

    # thumb
    thumb_gcs_path = '/muzeifeaturedart/' + base_filename + '_thumb.jpg'
    thumb = images.Image(image_result.content)
    thumb.resize(width=(thumb.width * 600 / thumb.height), height=600)
    thumb_contents = thumb.execute_transforms(output_encoding=images.JPEG, quality=40)
    gcs_file = gcs.open(thumb_gcs_path, 'w', content_type='image/jpeg')
    gcs_file.write(thumb_contents)
    gcs_file.close()

    # create the artwork entry
    new_artwork = FeaturedArtwork(
        title=title,
        byline=byline,
        image_url=CLOUD_STORAGE_ROOT_URL + image_gcs_path,
        thumb_url=CLOUD_STORAGE_ROOT_URL + thumb_gcs_path,
        details_url=details_url,
        publish_date=datetime.datetime
            .utcfromtimestamp(int(self.request.get('publishDate')) / 1000)
            .date())
    new_artwork.save()
    #self.response.out.write(json.dumps(obj, indent=2))
    self.response.set_status(200)
