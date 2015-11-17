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

from handlers.common import *
from models import FeaturedArtwork, _serialize_datetime


NEXT_PADDING = timedelta(minutes=5) # Next art should be requested at 2:00am UTC
MAX_HTTP_CACHE_AGE = timedelta(hours=1) # Cache HTTP cache requests for up to 1 hour


class FeaturedArtworkHandler(BaseHandler):
  def get(self):
    self.response.headers['Content-Type'] = 'application/json'
    body, headers = self.render_with_headers(self.request.get('callback', ''))
    for header in headers:
      self.response.headers[header] = headers[header]
    self.response.out.write(body)

  @pagecache('featured_artwork')
  def render_with_headers(self, callback):
    now = datetime.utcnow()
    headers = {}
    current = None

    # Get up to 5 artworks published earlier than 2 days from now, ordered by latest first
    latest_artworks = (FeaturedArtwork.all()
        .filter('publish_date <=', date.today() + timedelta(days=2))
        .order('-publish_date')
        .fetch(5))

    # Pick out the first artwork in that set that has actually been published
    for artwork in latest_artworks:
      if now >= datetime.combine(artwork.publish_date, START_TIME):
        current = artwork
        break

    ret_obj = dict()
    if current is not None:
      # Found the next featured artwork
      ret_obj = dict(
          title=current.title.strip(),
          byline=current.byline.strip(),
          imageUri=current.image_url,
          detailsUri=current.details_url)
      if current.thumb_url:
        ret_obj['thumbUri'] = current.thumb_url
      if current.attribution:
        ret_obj['attribution'] = current.attribution

      # The next update time is the next START_TIME
      next_start_time = datetime.combine(date.today(), START_TIME)
      while next_start_time < now:
        next_start_time += timedelta(hours=24)

      ret_obj['nextTime'] = _serialize_datetime(next_start_time + NEXT_PADDING)

      # Caches expire in an hour, but no later than the next start time minus padding
      cache_expire_time = min(
          now + MAX_HTTP_CACHE_AGE,
          next_start_time)
      expire_seconds = max(0, (cache_expire_time - now).total_seconds())

      # Note that this max-age header will be cached, so max-age may be off by the memcache
      # cache time which is set above to 60 seconds
      headers['Cache-Control'] = 'max-age=%d, must-revalidate, public' % expire_seconds
      headers['Expires'] = cache_expire_time.strftime('%a, %d %b %Y %H:%M:%S GMT')
      headers['Pragma'] = 'public'

    else:
      # Found no featured artwork; hopefully this is temporary; don't cache this response
      headers['Cache-Control'] = 'max-age=0, no-cache, no-store'
      headers['Pragma'] = 'no-cache'

    body = json.dumps(ret_obj, sort_keys=True)
    if callback:
      body = '%s(%s)' % (callback, body)

    return (body, headers)
