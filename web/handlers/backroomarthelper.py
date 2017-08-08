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

import json
import os
import re
import sys

import webapp2
from google.appengine.api import images
from google.appengine.api import urlfetch

sys.path.append(os.path.join(os.path.dirname(__file__),'../lib'))
from bs4 import BeautifulSoup
import cloudstorage as gcs

from handlers.common import *
from models import FeaturedArtwork


THUMB_HEIGHT=600
NO_CROP_TUPLE=(0, 0, 1, 1)


def add_art_from_external_details_url(publish_date, url):
  if FeaturedArtwork.all().filter('publish_date =', publish_date).get() != None:
    webapp2.abort(409, message='Artwork already exists for this date.')

  result = urlfetch.fetch(url)
  if result.status_code < 200 or result.status_code >= 300:
    webapp2.abort(400, message='Error processing URL: HTTP %d. Content: %s'
        % (result.status_code, result.content))

  soup = BeautifulSoup(result.content, 'html.parser')
  attribution = None

  if re.search(r'wikiart.org', url, re.I) or re.search(r'wikipaintings.org', url, re.I):
    attribution = 'wikiart.org'
    details_url = re.sub(r'#.+', '', url, re.I | re.S) + '?utm_source=Muzei&utm_campaign=Muzei'
    title = soup.find('h1').get_text()
    author = soup.find('a', class_='artist-name').get_text()
    completion_year = None
    try:
      completion_year = unicode(soup
          .find(text='Date:')
          .parent
          .find_next_sibling('span')
          .text).strip()
    except:
      pass
    byline = author + ((', ' + completion_year) if completion_year else '')
    image_url = get_wikiart_image_url(soup)
  elif re.search(r'metmuseum.org', url, re.I):
    attribution = 'metmuseum.org'
    details_url = re.sub(r'[#?].+', '', url, re.I | re.S) + '?utm_source=Muzei&utm_campaign=Muzei'
    title = soup.find('h2').get_text()
    author = ''
    try:
      author = unicode(soup.find(text='Artist:').parent.next_sibling).strip()
    except:
      pass
    author = re.sub(r'\s*\(.*', '', author)
    completion_year = None
    try:
      completion_year = unicode(soup.find(text='Date:').parent.next_sibling).strip()
    except:
      pass
    byline = author + ((', ' + completion_year) if completion_year else '')
    #image_url = soup.find('a', class_='download').attrs['href']
    image_url = soup.find('a', href=re.compile(r'original.*jpg')).attrs['href']
    image_url = re.search(r'http.*jpg', image_url).group(0)
  else:
    webapp2.abort(400, message='Unrecognized URL')

  if not title or not author or not image_url:
    webapp2.abort(500, message='Could not parse HTML')

  image_url, thumb_url = maybe_process_image(image_url,
      NO_CROP_TUPLE,
      publish_date.strftime('%Y%m%d') + ' ' + title + ' ' + byline)

  # create the artwork entry
  new_artwork = FeaturedArtwork(
      title=title.strip(),
      byline=byline.strip(),
      attribution=attribution,
      image_url=image_url,
      thumb_url=thumb_url,
      details_url=details_url,
      publish_date=publish_date)
  new_artwork.save()

  return new_artwork


def get_wikiart_image_url(soup):
  # TODO: use a cleaner method :(
  tmp = soup.find(class_='thumbnails_ref')['onclick']
  thumb_html_url = re.search(r'(/en.+?)\'', tmp).group(1)
  thumb_html_url = "http://www.wikiart.org%s" % thumb_html_url
  result = urlfetch.fetch(thumb_html_url)
  if result.status_code < 200 or result.status_code >= 300:
    webapp2.abort(400, message='Error processing URL: HTTP %d. Content: %s'
        % (result.status_code, result.content))

  thumb_html = json.loads(result.content)
  thumb_soup = BeautifulSoup(thumb_html, 'html.parser')
  max_thumb_width = 0
  max_thumb_url = None
  for thumb_title_el in thumb_soup.select('.thumbnail_title'):
    thumb_width = int(re.search(r'(\d+)x\d+', thumb_title_el.get_text()).group(1))
    if thumb_width > max_thumb_width:
      max_thumb_width = thumb_width
      max_thumb_url = thumb_title_el.parent.find('a')['href']

  return max_thumb_url


def maybe_process_image(image_url, crop_tuple, base_name):
  if CLOUD_STORAGE_ROOT_URL in image_url and crop_tuple == NO_CROP_TUPLE:
    return (image_url, None)

  image_result = urlfetch.fetch(image_url, deadline=20)
  if image_result.status_code < 200 or image_result.status_code >= 300:
    raise IOError('Error downloading image: HTTP %d.' % image_result.status_code)

  filename = re.sub(r'[^\w]+', '-', base_name.strip().lower()) + '.jpg'

  # main image
  image_gcs_path = CLOUD_STORAGE_BASE_PATH + '/fullres/' + filename
  # resize to max width 4000 or max height 2000
  image_contents = image_result.content
  image = images.Image(image_contents)
  edited = False
  if image.height > 2000:
    image.resize(width=(image.width * 2000 / image.height), height=2000)
    edited = True
  elif image.width > 4000:
    image.resize(width=4000, height=(image.height * 4000 / image.width))
    edited = True

  if crop_tuple != NO_CROP_TUPLE:
    image.crop(*crop_tuple)
    edited = True

  if edited:
    image_contents = image.execute_transforms(output_encoding=images.JPEG, quality=80)

  # upload with default ACLs set on the bucket  # or use options={'x-goog-acl': 'public-read'})
  gcs_file = gcs.open(image_gcs_path, 'w', content_type='image/jpeg')
  gcs_file.write(image_contents)
  gcs_file.close()

  # thumb
  thumb_gcs_path = CLOUD_STORAGE_BASE_PATH + '/thumbs/' + filename
  thumb = images.Image(image_result.content)
  thumb.resize(width=(thumb.width * THUMB_HEIGHT / thumb.height), height=THUMB_HEIGHT)

  if crop_tuple != NO_CROP_TUPLE:
    thumb.crop(*crop_tuple)
    edited = True

  thumb_contents = thumb.execute_transforms(output_encoding=images.JPEG, quality=40)
  gcs_file = gcs.open(thumb_gcs_path, 'w', content_type='image/jpeg')
  gcs_file.write(thumb_contents)
  gcs_file.close()

  return (CLOUD_STORAGE_ROOT_URL + image_gcs_path,
          CLOUD_STORAGE_ROOT_URL + thumb_gcs_path)

