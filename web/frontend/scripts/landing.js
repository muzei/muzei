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

const $deviceFrame = $('.device-frame');

// progressive enhancement
if (Modernizr.cssanimations &&
    Modernizr.svg &&
    Modernizr.csstransforms3d &&
    Modernizr.csstransitions) {

  setDeviceScene_('home');
  $('.device-screen').css('display', 'block');
  $deviceFrame.addClass('frameonly');

  $('.target-open-artdetail')
      .click(() => {
        setTimeout(() => setDeviceScene_('artdetail'), 0);
      });

  $(document)
      .on('mousedown', '.target-open-artdetail', () => $deviceFrame.addClass('mousedown'))
      .on('mouseup', () => $deviceFrame.removeClass('mousedown'));

  $deviceFrame.click(ev => setDeviceScene_('home'));

  FeaturedArt.get().then(data => {
    let image = data.thumbUri || data.imageUri;
    $('.layer-wall, .layer-wall-blurred').css('background-image', `url('${image}')`);
    $('.artdetail-title').text(data.title);
    $('.artdetail-byline').text(data.byline);
  });
}

function setDeviceScene_(scene) {
  $deviceFrame.attr('data-scene', scene);
  $('.device-scene').removeClass('is-active');
  $(`.scene-${scene}`).addClass('is-active');
}