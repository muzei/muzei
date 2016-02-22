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

from datetime import datetime, date, timedelta
import os
import re
import sys
import random

from handlers import backroomarthelper
from handlers.common import *
from models import FeaturedArtwork


SANITIZE_ARTWORK_KEY_RE = re.compile(r'\?.*', re.I | re.S)


def sanitized_artwork_key(artwork):
  return SANITIZE_ARTWORK_KEY_RE.sub('', artwork.details_url)


class PickRandomArtworkTaskHandler(BaseHandler):
  def get(self):
    # Fetch latest 1000 artworks
    latest_artworks = (FeaturedArtwork.all()
        .order('-publish_date')
        .fetch(1000))

    # List dates for which artwork exists
    dates_with_existing_art = set(a.publish_date for a in latest_artworks)

    # List target dates that we want artwork for, but for which no artwork exists
    target_dates = [date.today() + timedelta(days=n) for n in range(-1, 9)]
    target_dates = [d for d in target_dates if d not in dates_with_existing_art]

    for target_date in target_dates:
      self.response.out.write('looking for artwork for date ' + str(target_date) + '<br>')

      # Create a blacklist of the most recent 200 artwork
      # (don't want to repeat one of the last 200!)
      blacklist_artwork_keys = set(sanitized_artwork_key(a) for a in latest_artworks[:200])
      if len(blacklist_artwork_keys) < 5:
        blacklist_artwork_keys = set() # should never happen, but just in case of a reset

      # Pick from one of the oldest 500, excluding artwork in the blacklist
      random_artwork = None
      while True:
        random_artwork = random.choice(latest_artworks[500:])
        key = sanitized_artwork_key(random_artwork)
        if 'wikiart.org' in key or 'wikipaintings.org' in key or 'metmuseum.org' in key:
          if key not in blacklist_artwork_keys:
            break

      target_details_url = str(random_artwork.details_url)
      self.response.out.write('recycling ' + target_details_url + ' for date ' + str(target_date) + '<br>')

      backroomarthelper.add_art_from_external_details_url(
          target_date,
          target_details_url)

    self.response.out.write('done<br>')
