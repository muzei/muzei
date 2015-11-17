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

"""Defines models for the Muzei API server."""

import datetime

from google.appengine.ext import db


class UTC(datetime.tzinfo):
  """A UTC tzinfo object."""

  def utcoffset(self, dt):
    return datetime.timedelta(0)

  def tzname(self, dt):
    return 'UTC'

  def dst(self, dt):
    return datetime.timedelta(0)

utc = UTC()


def _serialize_datetime(d):
  return d.replace(tzinfo=utc).isoformat()


class FeaturedArtwork(db.Model):
  """A featured artwork."""
  NO_PUBLISH_DATE = datetime.datetime(3000, 1, 1)

  title = db.StringProperty()
  byline = db.StringProperty()
  attribution = db.StringProperty()
  image_url = db.LinkProperty()
  thumb_url = db.LinkProperty()
  details_url = db.LinkProperty()
  publish_date = db.DateProperty()
