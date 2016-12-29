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

const DEFAULT_SLOP = 10;
const DEFAULT_FLING_VELOCITY = 600;


export class CarouselGestureHelper {
  constructor($el, options) {
    this.$el = $el;
    this.options = Object.assign({}, {
      slop: DEFAULT_SLOP,
      cursor: 'grabbing',
      flingVelocity: DEFAULT_FLING_VELOCITY,
      onPanStart: () => {},
      onPan: () => {},
      onPanEnd: () => {}
    }, options);
    this.panning = false;
    this.setup_();
  }

  isPanning() {
    return this.panning;
  }

  set paused(val) {
    this.paused_ = !!val;
  }

  setup_() {
    this.$el.on('dragstart', e => e.preventDefault());

    // mouse
    let mouseMoveHandler_ = e => {
      if (this.handleMove_(e.clientX)) {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    let mouseUpHandler_ = e => {
      this.handleUp_(e.clientX);
      e.preventDefault();
      e.stopPropagation();
      $(window)
          .off('mousemove', mouseMoveHandler_)
          .off('mouseup', mouseUpHandler_);
    };
    this.$el.on('mousedown', e => {
      this.handleDown_(e.clientX);
      $(window)
          .on('mousemove', mouseMoveHandler_)
          .on('mouseup', mouseUpHandler_);
    });

    // touch
    let touchMoveHandler_ = e => {
      if (this.handleMove_(e.originalEvent.touches[0].clientX)) {
        e.preventDefault();
        e.stopPropagation();
      }
    };
    let touchEndHandler_ = e => {
      this.handleUp_(e.originalEvent.changedTouches[0].clientX);
      $(window)
          .off('touchmove', touchMoveHandler_)
          .off('touchend touchcancel', touchEndHandler_);
    };
    this.$el.on('touchstart', e => {
      this.handleDown_(e.originalEvent.touches[0].clientX);
      $(window)
          .on('touchmove', touchMoveHandler_)
          .on('touchend touchcancel', touchEndHandler_);
    });
  }

  handleDown_(x) {
    this.downX_ = x;
  }

  handleMove_(x) {
    let deltaX = x - this.downX_;
    if (!this.paused_ && !this.panning && Math.abs(deltaX) > this.options.slop) {
      this.panning = true;
      this.options.onPanStart({});
      this.draggingScrim_ = this.buildDraggingScrim_().appendTo(document.body);
    }
    if (this.panning) {
      this.options.onPan({deltaX});
    }

    let time = Number(new Date()) / 1000;
    this.lastVelocityX_ = this.lastMoveTime_
        ? Math.round((deltaX - this.lastDeltaX_) / (time - this.lastMoveTime_))
        : 0;

    this.lastDeltaX_ = deltaX;
    this.lastMoveTime_ = time;
    return this.panning;
  }

  handleUp_(x) {
    if (!this.panning) {
      return;
    }

    this.options.onPanEnd({
      isFling: Math.abs(this.lastVelocityX_) > this.options.flingVelocity,
      flingDirection: (this.lastVelocityX_ > 0) ? 'left' : 'right'
    });
    this.draggingScrim_.remove();
    this.draggingScrim_ = null;
    this.panning = false;
  }

  buildDraggingScrim_() {
    let cursor = this.options.cursor;
    if (cursor == 'grabbing') {
      cursor = `-webkit-${cursor}`;
    }

    return $('<div>')
        .css({
          position: 'fixed',
          left: 0,
          top: 0,
          right: 0,
          bottom: 0,
          zIndex: 9999,
          cursor
        });
  }
}
