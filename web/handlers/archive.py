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

import base64
import datetime
import os
import re
import gzip
import json
import sys
import StringIO

from google.appengine.api import images
from google.appengine.api import urlfetch

sys.path.append(os.path.join(os.path.dirname(__file__),'../lib'))
import cloudstorage as gcs

from handlers.common import *
from models import FeaturedArtwork


ARCHIVE_START_DATE = datetime.date(2014, 2, 11)
CLOUD_STORAGE_ARCHIVE_PATH = CLOUD_STORAGE_BASE_PATH + '/archivemeta'
ARCHIVE_IMAGE_SIZE = 250  # 250 pixels square

# two types of archives:
# full-month: 201406, contains everything for that month
# partial month: 20140620, contains everything up to and including that day of the month

class UpdateArchiveTaskHandler(BaseHandler):
  def get(self):
    now = datetime.datetime.utcnow()
    if self.request.get('datetime'):
      now = datetime.datetime.strptime(self.request.get('datetime'), '%Y-%m-%dT%H:%M:%S')

    current_date = (now.date() if now.time() > START_TIME
                    else now.date() - datetime.timedelta(days=1))
    current_month = current_date.month

    # list the expected archives up until this point, starting with current month's archive
    expected_archives = []

    if current_date > ARCHIVE_START_DATE:
      if (current_date + datetime.timedelta(days=1)).month != current_month:
        # end of the month
        expected_archives.append((current_date.year, current_date.month))
      else:
        # partial month for this month
        expected_archives.append((current_date.year, current_date.month, current_date.day))

    # list all other months
    if current_date.month != ARCHIVE_START_DATE.month or current_date.year != ARCHIVE_START_DATE.year:
      current_date = current_date.replace(day=1)
      while True:
        current_date -= datetime.timedelta(days=1) # previous month
        current_date = current_date.replace(day=1)
        expected_archives.append((current_date.year, current_date.month))
        if current_date <= ARCHIVE_START_DATE:
          break

    # at this point expected_archives has a list of all archives that should be built

    # list current archive items to determine which archives are missing
    current_archives = []
    current_archive_files = gcs.listbucket(CLOUD_STORAGE_ARCHIVE_PATH)
    self.response.out.write('<h1>current archives</h1>')
    for archive_file in current_archive_files:
      m = re.search(r'((?:\d){4})((?:\d){2})((?:\d){2})?\.txt', archive_file.filename)
      if m:
        if m.group(3):
          archive = (int(m.group(1)), int(m.group(2)), int(m.group(3)))
        else:
          archive = (int(m.group(1)), int(m.group(2)))
        current_archives.append(archive)
        self.response.out.write(repr(archive) + '<br>')
      #self.response.out.write(archivemeta.filename + '\n')
    current_archives = set(current_archives)
    expected_archives = set(expected_archives)
    missing_archives = expected_archives.difference(current_archives)

    # generate the missing archives
    self.response.out.write('<h1>building missing archives</h1>')
    for archive in missing_archives:
      self.response.out.write('<h2>' + repr(archive) + '</h2>')
      # when building an archive, try to start from an existing archive
      # find the latest archive from this month as a starting point
      other_archives_from_month = filter(
          lambda x: len(x) == 3 and x[0] == archive[0] and x[1] == archive[1],
          current_archives)
      latest_current_archive_from_month = None
      latest_archive_gcs_path = None

      archive_metadata = []
      archive_image_blobs = []

      if other_archives_from_month:
        latest_current_archive_from_month = reduce(
            lambda x, y: (x[0], x[1], max(x[2], y[2])), other_archives_from_month)
        self.response.out.write('starting from archive ' + repr(latest_current_archive_from_month) + '<br>')

        existing_archive_name = '%04d%02d%02d' % latest_current_archive_from_month
        try:
          latest_archive_gcs_path = CLOUD_STORAGE_ARCHIVE_PATH + '/' + existing_archive_name + '.txt'
          existing_archive = gcs.open(latest_archive_gcs_path)
          content = gzip_decompress(existing_archive.read())
          existing_archive_lines = content.split('\n')
          existing_archive.close()
          archive_metadata = json.loads(existing_archive_lines[0])
          archive_image_blobs = filter(lambda x: len(x) > 0, existing_archive_lines[1:])
        except:
          self.response.out.write('error reading from existing archive, starting from scratch<br>')
          latest_current_archive_from_month = None
          latest_archive_gcs_path = None

      # construct the query
      query_from = None
      if latest_current_archive_from_month:
        # get everything after the latest archive this month
        query_from = datetime.date(*latest_current_archive_from_month) + datetime.timedelta(days=1)
      else:
        # get everything from this month
        query_from = datetime.date(
            archive[0], archive[1], archive[2] if len(archive) == 3 else 1).replace(day=1)
      query_from = max(ARCHIVE_START_DATE, query_from)

      query_to = None
      archive_name = None
      if len(archive) == 3:
        # partial month archive
        archive_name = '%04d%02d%02d' % archive
        query_to = datetime.date(*archive)
      else:
        # full month archive
        archive_name = '%04d%02d' % archive
        next_month = datetime.date(archive[0], archive[1], 1)
        if next_month.month == 12:
          next_month = next_month.replace(year=next_month.year + 1, month=1)
        else:
          next_month = next_month.replace(month=next_month.month + 1)
        query_to = next_month - datetime.timedelta(days=1)

      # fetch artworks that match this query
      artwork_objs = (FeaturedArtwork.all()
          .order('publish_date')
          .filter('publish_date >=', query_from)
          .filter('publish_date <=', query_to)
          .fetch(1000))
      for artwork_obj in artwork_objs:
        metadata_item = dict(
            publish_date=artwork_obj.publish_date.isoformat(),
            title=artwork_obj.title,
            byline=artwork_obj.byline,
            thumb_url=artwork_obj.thumb_url,
            details_url=artwork_obj.details_url,)

        # fetch the image
        image_result = urlfetch.fetch(artwork_obj.thumb_url)
        if image_result.status_code < 200 or image_result.status_code >= 300:
          raise IOError('Error downloading image: HTTP %d.' % image_result.status_code)

        # resize and crop thumb
        thumb = images.Image(image_result.content)
        if thumb.width > thumb.height:
          thumb.resize(width=4000, height=ARCHIVE_IMAGE_SIZE)
          thumb.crop(
              (float(thumb.width - thumb.height) / thumb.width) / 2, 0.,
              1 - (float(thumb.width - thumb.height) / thumb.width) / 2, 1.)
        else:
          thumb.resize(width=ARCHIVE_IMAGE_SIZE, height=4000)
          thumb.crop(
              0., (float(thumb.height - thumb.width) / thumb.height) / 2,
              1., 1 - (float(thumb.height - thumb.width) / thumb.height) / 2)

        # compute average color
        histogram = thumb.histogram()
        avg_color = tuple([int(x) for x in img_weighed_average(histogram)])
        avg_color_hex = "#%0.2X%0.2X%0.2X" % avg_color
        metadata_item['color'] = avg_color_hex

        # export thumb
        thumb_data_uri = 'data:image/jpeg;base64,' + base64.b64encode(
            thumb.execute_transforms(output_encoding=images.JPEG, quality=40))

        # append the metadata
        archive_metadata.append(metadata_item)
        archive_image_blobs.append(thumb_data_uri)

      self.response.out.write('query: from ' + repr(query_from) + ' to ' + repr(query_to) + '<br>')
      self.response.out.write('artworks: ' + str(len(artwork_objs)) + '<br>')
      #self.response.out.write('<pre>' + json.dumps(archive_metadata, indent=2) + '</pre>')

      # create the archive contents
      s = json.dumps(archive_metadata) + '\n'
      for blob in archive_image_blobs:
        s += blob + '\n'

      # gzip and write the archive
      gcs_path = CLOUD_STORAGE_ARCHIVE_PATH + '/' + archive_name + '.txt'
      self.response.out.write('writing to: ' + gcs_path + '<br>')
      gcsf = gcs.open(gcs_path, 'w',
          content_type='text/plain', options={'content-encoding':'gzip'})
      gcsf.write(gzip_compress(s))
      gcsf.close()

      # delete the previous archive
      if latest_archive_gcs_path:
        gcs.delete(latest_archive_gcs_path)


def gzip_compress(s):
  stringio = StringIO.StringIO()
  f = gzip.GzipFile(fileobj=stringio, mode='wb')
  f.write(s)
  f.close()
  return stringio.getvalue()


def gzip_decompress(s):
  stringio = StringIO.StringIO(s)
  f = gzip.GzipFile(fileobj=stringio)
  ret = f.read()
  f.close()
  return ret


# https://gist.github.com/gregorynicholas/1970604
def img_weighed_average(hist):
  '''Returns a tuple of floats for the weighted average of RGB values.'''
  def _weighted_average(values):
    '''Multiply the pixel counts (the values in the histogram)
       by the pixel values (the index in the values list).

       Then add them, to get a weighted sum.

       Then divide by the number of pixels to
       get the weighted average.'''
    weighted_sum = sum(i * values[i] for i in range(len(values)))
    num_pixels = sum(values)
    weighted_average = weighted_sum / num_pixels
    return weighted_average

  red_weighed_average = _weighted_average(hist[0])
  green_weighed_average = _weighted_average(hist[1])
  blue_weighed_average = _weighted_average(hist[2])
  return red_weighed_average, green_weighed_average, blue_weighed_average

