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
import json
import logging
import os
import re
import sys
import random

from handlers import backroomarthelper
from handlers.common import *
from models import FeaturedArtwork


SANITIZE_ARTWORK_KEY_RE = re.compile(r'\?.*', re.I | re.S)

LOOKAHEAD_DAYS = 60


def artwork_key(details_url):
  return SANITIZE_ARTWORK_KEY_RE.sub('', details_url)


class PickRandomArtworkTaskHandler(BaseHandler):
  def get(self):
    ARTWORKS = json.loads(open(os.path.join(os.path.split(__file__)[0], 'lt-artworks.json')).read())

    # ARTWORKS = filter(lambda a: '_stars' in a and a['_stars'] >= 1, ARTWORKS)

    # Fetch latest 300 artworks (for blacklisting)
    latest_artworks = (FeaturedArtwork.all()
        .order('-publish_date')
        .fetch(300))

    # List dates for which artwork exists
    dates_with_existing_art = set(a.publish_date for a in latest_artworks)

    # List target dates that we want artwork for, but for which no artwork exists
    target_dates = [date.today() + timedelta(days=n) for n in range(-1, LOOKAHEAD_DAYS)]
    target_dates = [d for d in target_dates if d not in dates_with_existing_art]

    # Create a blacklist of keys to avoid repeats
    blacklist = set(artwork_key(a.details_url) for a in latest_artworks)

    logging.debug('starting blacklist size: %d' % len(blacklist))

    chosen_artworks = []

    for target_date in target_dates:
      # Pick from available artworks, excluding artwork in the blacklist
      random_artwork = None
      while True:
        if len(ARTWORKS) == 0:
          logging.error('Ran out of artworks to choose from, cannot continue')
          return

        random_artwork = random.choice(ARTWORKS)
        key = artwork_key(random_artwork['detailsUri'])
        if key not in blacklist:
          # Once chosen, remove it from the list of artworks to choose next
          ARTWORKS.remove(random_artwork)
          chosen_artworks.append(random_artwork)
          break

      target_details_url = str(random_artwork['detailsUri'])
      logging.debug('%(date)s: setting to %(url)s' % dict(url=target_details_url, date=target_date))

      # Store the new artwork
      if self.request.get('dry-run', '') != 'true':
        new_artwork = FeaturedArtwork(
            title=random_artwork['title'],
            byline=random_artwork['byline'],
            attribution=random_artwork['attribution'],
            image_url=random_artwork['imageUri'],
            thumb_url=random_artwork['thumbUri'],
            details_url=random_artwork['detailsUri'],
            publish_date=target_date)
        new_artwork.save()

    if self.request.get('output', '') == 'html':
      self.response.out.write(get_html(artworks_json=json.dumps(chosen_artworks)))

    # Finish up
    logging.debug('done')


def get_html(**kwargs):
  return '''
<!doctype html>
<body>

<style>

body {
  margin: 64px;
  display: flex;
  flex-flow: row wrap;
}

img {
  width: 128px;
  height: 128px;
  object-fit: cover;
  object-position: 50%% 50%%;
  margin: 4px;
}

</style>

<script>

let ARTWORKS = %(artworks_json)s;

ARTWORKS.forEach(artwork => {
  let img = document.createElement('img');
  img.setAttribute('src', artwork.thumbUri);
  document.body.appendChild(img);
});

</script>

</body>
</html>
''' % kwargs