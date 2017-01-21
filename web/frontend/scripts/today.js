/**
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {FeaturedArt} from './_featuredart';

const META_SPACING = 30;
const META_MAX_WIDTH = 300;
const META_SIDE_MIN_WIDTH = 200;
const META_STACKED_MAX_HEIGHT = 68;

class TodayController {
  constructor() {
    this.$loadingSpinner = $('.loading-spinner');
    this.$artworkContainer = $('.artwork-container');
    this.$artwork = $('.artwork');
    this.$artworkLink = $('.artwork-link');
    this.$meta = $('.meta');
    this.setLoaded(false);
    FeaturedArt.get().then(data => this.load(data));
    $(window).on('resize', e => this.resize());
  }

  setLoaded(loaded) {
    $(document.body).toggleClass('loaded', !!loaded);
  }

  load(data) {
    let uri = data.imageUri;
    loadImage_(uri).then(img => {
      this.artworkAspectRatio = img.naturalWidth / Math.max(1, img.naturalHeight);
      this.$artwork.css('background-image', `url('${uri}')`);
      this.$artworkLink.attr('href', data.detailsUri);
      this.$meta.find('.title').html(data.title);
      this.$meta.find('.byline').text(data.byline);
      this.$meta.find('.attribution').text(data.attribution);
      this.resize();
      this.setLoaded(true);
    });
  }

  resize() {
    if (!this.artworkAspectRatio) {
      return;
    }

    let containerWidth = this.$artworkContainer.outerWidth();
    let containerHeight = Math.max(1, this.$artworkContainer.outerHeight());

    let artworkHeight = containerHeight;
    let artworkWidth = this.artworkAspectRatio * artworkHeight;

    // layouts in order of preference
    // 1. artwork horizontally centered, meta to right
    // 2. artwork as horizontally centered as possible, meta to right
    // 3. artwork horizontally centered, meta below
    // 4. artwork vertically centered, meta below
    // 5. artwork as vertically centered as possible, meta below

    // start with layout #1
    let artworkRect = {
      left: (containerWidth - artworkWidth) / 2,
      top: (containerHeight - artworkHeight) / 2,
      width: Math.round(artworkWidth),
      height: Math.round(artworkHeight)
    };

    let metaRect = {
      left: artworkRect.left + artworkWidth + META_SPACING,
      top: artworkRect.top,
      maxWidth: META_MAX_WIDTH
    };

    let metaDistToRightEdge = containerWidth - (metaRect.left + META_SIDE_MIN_WIDTH);

    if (metaDistToRightEdge < 0) {
      // switch to layout #2
      metaRect.left += metaDistToRightEdge;
      artworkRect.left += metaDistToRightEdge;

      if (artworkRect.left < 0) {
        // switch to layout #3
        let metaHeight = computeElementHeightAtWidth_(this.$meta, META_MAX_WIDTH);

        artworkHeight = containerHeight - (metaHeight + META_SPACING);
        artworkWidth = this.artworkAspectRatio * artworkHeight;

        artworkRect = {
          left: (containerWidth - artworkWidth) / 2,
          top: (containerHeight - (artworkHeight + metaHeight + META_SPACING)) / 2,
          width: Math.round(artworkWidth),
          height: Math.round(artworkHeight)
        };

        metaRect = {
          left: artworkRect.left,
          top: artworkRect.top + artworkHeight + META_SPACING
        };

        if (artworkRect.left < 0) {
          // switch to layout #4
          artworkWidth = containerWidth;
          artworkHeight = artworkWidth / this.artworkAspectRatio;

          artworkRect = {
            left: 0,
            top: (containerHeight - artworkHeight) / 2,
            width: Math.round(artworkWidth),
            height: Math.round(artworkHeight)
          };

          metaRect = {
            left: artworkRect.left,
            top: artworkRect.top + artworkHeight + META_SPACING
          };

          let metaDistToBottomEdge = containerHeight - (metaRect.top + metaHeight);
          if (metaDistToBottomEdge < 0) {
            // switch to layout #5
            metaRect.top += metaDistToBottomEdge;
            artworkRect.top += metaDistToBottomEdge;
          }
        }
      }
    }

    this.$artwork.css(artworkRect);
    this.$meta.css(metaRect);
  }
};


function computeElementHeightAtWidth_(el, width) {
  let $clone = $(el)
      .clone()
      .css({
        width: width,
        position: 'fixed',
        left: -9999
      })
      .appendTo(document.body);
  let height = $clone.outerHeight();
  $clone.remove();
  return height;
}


function loadImage_(url) {
  return new Promise((resolve, reject) => {
    let img = document.createElement('img');
    img.onload = e => {
      resolve(img);
    };
    img.onerror = e => {
      reject(e);
    };
    img.src = url;
  });
}


new TodayController();
