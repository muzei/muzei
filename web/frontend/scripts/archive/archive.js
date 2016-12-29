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

import {CarouselGestureHelper} from './_carousel-gesture-helper';

const MONTHS = [
    'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
    'September', 'October', 'November', 'December'];
const DAYS = [
    'Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];

const HOUR_MILLIS = 60 * 60 * 1000;
const DAY_MILLIS = 24 * HOUR_MILLIS;

const START_DATE = new Date('2014-02-12');
const START_MONTH = [START_DATE.getFullYear(), START_DATE.getMonth() + 1];

const TODAY = new Date();
const TODAY_MONTH = [TODAY.getFullYear(), TODAY.getMonth() + 1];

const NUM_CAROUSEL_PAGES = monthDistance_(START_MONTH, TODAY_MONTH) + 1;

const MONTH_SPACING_WIDE = 80; // pixels
const MONTH_SPACING_NARROW = 40; // pixels

const ARCHIVE_BASE_URL = '//storage.googleapis.com/muzeifeaturedart/archivemeta/';


class ArchiveController {
  constructor() {
    this.currentScrollX = 0;
    this.$carousel = $('.month-carousel');
    this.$carouselContainer = $('.month-carousel-container');
    this.pageWidth = 0;
    this.selectedMonth = 0;

    this.initState();

    // carousel setup
    this.setupCarouselLayoutWatchers();
    this.setupCarouselMousewheelNavigation();
    this.setupCarouselKeyboardNavigation();
    this.setupCarouselClickNavigation();
    this.setupCarouselGestureNavigation();
  }

  initState() {
    let m = document.location.href.match(/\/archive\/(.+)/);
    if (m && isMonthInValidRange_(monthFromUrlPath_(m[1]))) {
      this.selectMonth(monthFromUrlPath_(m[1]));
    } else {
      this.selectMonth(TODAY_MONTH);
    }
  }

  setupCarouselLayoutWatchers() {
    $(window).resize(() => {
      let newPageWidth = this.$carousel.width();
      if (this.pageWidth != newPageWidth) {
        this.pageWidth = newPageWidth;
        this.relayout();
      }
    });
  }

  setupCarouselMousewheelNavigation() {
    let mousewheelSnapTimeout = 0;
    $(document).on('wheel', e => {
      if (Math.abs(e.originalEvent.deltaX)) {
        e.preventDefault();
      }
      if (Math.abs(e.originalEvent.deltaX) > Math.abs(e.originalEvent.deltaY)) {
        this.scrollBy(e.originalEvent.deltaX);

        if (mousewheelSnapTimeout) {
          window.clearTimeout(mousewheelSnapTimeout);
        }

        mousewheelSnapTimeout = window.setTimeout(() =>
            this.selectMonth(this.computeMonthAtScrollPosition()), 100);
      }
    });
  }

  setupCarouselKeyboardNavigation() {
    $(document).on('keydown', e => {
      if (e.keyCode == 37) {
        this.selectMonth(prevMonth_(this.selectedMonth));
        e.preventDefault();
      } else if (e.keyCode == 39) {
        this.selectMonth(nextMonth_(this.selectedMonth));
        e.preventDefault();
      }
    });
  }

  setupCarouselClickNavigation() {
    $(document).on('click', '.month .click-screen', e =>
        this.selectMonth(monthFromId_($(e.currentTarget).parent().attr('id'))));
  }

  setupCarouselGestureNavigation() {
    let downScrollX;
    let gesture = new CarouselGestureHelper(this.$carouselContainer, {
      onPanStart: e => downScrollX = this.currentScrollX,
      onPan: e => this.scrollTo(downScrollX - e.deltaX),
      onPanEnd: e => {
        if (e.isFling) {
          this.selectMonth(
              (e.flingDirection == 'right' ? nextMonth_ : prevMonth_)(this.selectedMonth));
        } else {
          this.selectMonth(this.computeMonthAtScrollPosition());
        }
      }
    });

    // when something is scrolling, pause carousel gestures
    let resumeTimeout;
    this.$carouselContainer.get(0).addEventListener('scroll', e => {
      gesture.paused = true;
      if (resumeTimeout) {
        window.clearTimeout(resumeTimeout);
      }
      resumeTimeout = window.setTimeout(() => gesture.paused = false, 100);
    }, true);
  }

  renderMonth(month, force) {
    if (!isMonthInValidRange_(month)) {
      return;
    }

    if (!this.pageWidth) {
      this.pageWidth = this.$carousel.width();
    }

    let $month = monthNode_(month);
    let needsRender = !!force;
    if (!$month.length) {
      $month = $('<div>')
          .css('left', this.getMonthX(month) + 'px')
          .addClass('month')
          .attr('id', 'month-' + monthKey_(month))
          .appendTo(this.$carousel);
      needsRender = true;
    }

    if (needsRender) {
      $month.empty();
      this.renderMonthContents(month);
    }

    return $month;
  }

  relayout() {
    $('.month').each((_, node) => {
      let $month = $(node);
      let month = monthFromId_($month.attr('id'));
      $month.css('left', this.getMonthX(month) + 'px');
    });

    this.selectMonth(this.selectedMonth);
  }

  selectMonth(month, move) {
    if (move === undefined) {
      move = true;
    }

    if (!isMonthInValidRange_(month)) {
      this.selectMonth(this.selectedMonth, move);
      return;
    }

    this.renderMonth(month);
    let $month = monthNode_(month);

    if (move) {
      this.scrollTo(($month.offset().left - this.$carousel.offset().left), true);
      let newUrl = `/archive/${monthUrlPath_(month)}`;
      window.history.replaceState('', '', newUrl);
    }

    $('.month').removeClass('active');
    $month.toggleClass('active', true);

    this.renderMonth(prevMonth_(month));
    this.renderMonth(nextMonth_(month));
    this.selectedMonth = month;
  }

  scrollBy(deltaX) {
    return this.scrollTo(this.currentScrollX + deltaX, false);
  }

  scrollTo(position, animate) {
    animate = !!animate;

    let minScrollX = this.getMonthX(START_MONTH);
    let maxScrollX = this.getMonthX(TODAY_MONTH);

    this.currentScrollX = Math.min(maxScrollX, Math.max(minScrollX, position));

    let translatePosition = this.currentScrollX;

    this.$carousel
        .toggleClass('animate', animate)
        .css('transform', `translate3d(${-translatePosition}px,0,0)`);

    if (this.selectedMonth) {
      let m = this.computeMonthAtScrollPosition();
      if (monthDistance_(m, this.selectedMonth) != 0) {
        this.selectMonth(this.computeMonthAtScrollPosition(), false);
      }
    }

    return true;
  }

  computeMonthAtScrollPosition() {
    let minDistanceToScrollX = 999999;
    let minDistanceMonth = null;

    for (let month = START_MONTH;
         monthDistance_(month, TODAY_MONTH) >= 0;
         month = nextMonth_(month)) {
      let monthDistanceToScrollX = Math.abs(this.getMonthX(month) - this.currentScrollX);
      if (monthDistanceToScrollX < minDistanceToScrollX) {
        minDistanceMonth = month;
        minDistanceToScrollX = monthDistanceToScrollX;
      }
    }

    return minDistanceMonth;
  }

  renderMonthContents(month) {
    let $month = monthNode_(month);

    $('<div>')
        .addClass('click-screen')
        .appendTo($month);

    // header row
    let $monthHeader = $('<div>')
        .text(MONTHS[month[1] - 1] + ' ' + month[0])
        .addClass('month-row month-header').appendTo($month);

    // weekdays row
    let $weekdayLabelsRow = $('<div>')
        .addClass('month-row weekday-labels')
        .appendTo($month);
    for (let dow = 0; dow < 7; dow++) {
      $('<div>')
          .text(DAYS[dow])
          .addClass('cell')
          .appendTo($weekdayLabelsRow);
    }

    // days
    let today = new Date(Date.now() - 2 * HOUR_MILLIS); // new paintings at 2AM UTC
    let todayYear = today.getUTCFullYear();
    let todayMonth = today.getUTCMonth();
    let todayDate = today.getUTCDate();

    let firstOfMonthDate = new Date();
    firstOfMonthDate.setUTCHours(0);
    firstOfMonthDate.setUTCMinutes(0);
    firstOfMonthDate.setUTCSeconds(0);
    firstOfMonthDate.setUTCDate(1);
    firstOfMonthDate.setUTCFullYear(month[0]);
    firstOfMonthDate.setUTCMonth(month[1] - 1);

    let dowFirstOfMonth = firstOfMonthDate.getUTCDay();
    let firstVisibleDate = new Date(firstOfMonthDate - dowFirstOfMonth * DAY_MILLIS);
    let date = firstVisibleDate;
    let week = 0;
    let $daysRow = $('<div>')
        .addClass('month-row days')
        .appendTo($month);

    let monthEnded = true;
    while (++week < 10) {
      for (let dow = 0; dow < 7; dow++) {
        let dayMonth = date.getUTCMonth();
        let dayYear = date.getUTCFullYear();
        let dayDate = date.getUTCDate();
        let skipDay = false;

        let classes = 'cell ';
        if (dayMonth != month[1] - 1) {
          // previous month
          skipDay = true;
          classes += 'skipped ';
          if ((dayMonth < month[1] - 1) || (month[1] == 1 && dayMonth == 11)) {
            classes += 'before ';
          } else {
            break;
          }
        } else if (date < START_DATE) {
          skipDay = true;
          classes += 'skipped before ';
        } else if (dayYear == todayYear && dayMonth == todayMonth && dayDate == todayDate) {
          classes += 'today ';
        } else if (date < today) {
          classes += 'past ';
        } else {
          // future, don't render
          monthEnded = false;
          break;
        }

        let $dayCell = $('<div>')
            .addClass(classes)
            .appendTo($daysRow);

        if (!skipDay) {
          $dayCell.attr('id', `day-${dayKey_(date)}`);
        }

        if (!skipDay) {
          // generate day cell innards
          let $image = $('<div>')
              .addClass('image')
              .appendTo($dayCell);

          let $meta = $('<div>')
              .addClass('meta')
              .appendTo($dayCell);

          // show overlay and layer
          let $overlay = $('<a>')
              .addClass('overlay-link')
              .attr('target', '_blank')
              .append($('<div>').addClass('date').text(date.getUTCDate()))
              .appendTo($dayCell);
        }

        // move on to next day
        date = new Date(date.getTime() + DAY_MILLIS);
      }

      if (date.getUTCMonth() != month[1] - 1) {
        break;
      }
    }

    // fetch archive data
    let archiveKey = dayKey_(today);
    if (monthEnded) {
      archiveKey = monthKey_(month);
    }

    loadArchiveData_(archiveKey);
  }

  getMonthX(month) {
    let monthSpacing = (this.pageWidth <= 320)
        ? MONTH_SPACING_NARROW
        : MONTH_SPACING_WIDE;
    return -monthDistance_(month, TODAY_MONTH) * (this.pageWidth + monthSpacing);
  }
}


function monthNode_(month) {
  return $(`#month-${monthKey_(month)}`);
}


function dayNode_(date) {
  return $(`#day-${dayKey_(date)}`);
}


function isMonthInValidRange_(month) {
  return monthDistance_(START_MONTH, month) >= 0 &&
         monthDistance_(month, TODAY_MONTH) >= 0;
}


function dayKey_(date) {
  let y = date.getUTCFullYear();
  let m = date.getUTCMonth() + 1;
  let d = date.getUTCDate();
  return y + (m < 10 ? '0' : '') + m + (d < 10 ? '0' : '') + d;
}


function monthKey_(month) {
  return month[0] + (month[1] < 10 ? '0' : '') + month[1];
}


function monthUrlPath_(month) {
  return month[0] + '/' + month[1];
}


function monthFromUrlPath_(path) {
  let m = path.match(/(\d+)(\/(\d+))?/);
  return [parseInt(m[1], 10),
          parseInt(m[3] || 1, 10)];
}


function monthFromId_(id) {
  return [parseInt(id.substring(6, 10), 10),
          parseInt(id.substring(10, 12), 10)];
}


function monthDistance_(month1, month2) {
  return (month2[0] - month1[0]) * 12 + (month2[1] - month1[1]);
}


function prevMonth_(month) {
  if (month[1] == 1) {
    return [month[0] - 1, 12];
  }

  return [month[0], month[1] - 1];
}


function nextMonth_(month) {
  if (month[1] == 12) {
    return [month[0] + 1, 1];
  }

  return [month[0], month[1] + 1];
}


// loosely based on http://www.kylescholz.com/blog/2010/01/progressive_xmlhttprequest_1.html
function loadArchiveData_(archiveKey) {
  let xhr = new XMLHttpRequest();
  let buffer = '';
  let index = 0;

  let itemsMeta = null;
  let imageBlobs = [];
  let imageBlobsProcessed = 0;

  // construct and execute XHR
  xhr.addEventListener('load', e => processMoreResponseText_(), false);
  xhr.addEventListener('progress', e => processMoreResponseText_(), false);
  xhr.addEventListener('error', e => { /* todo */ }, false);
  xhr.addEventListener('abort', e => { /* todo */ }, false);

  xhr.open('get', `${ARCHIVE_BASE_URL}${archiveKey}.txt`, true);
  xhr.overrideMimeType('text/plain');
  //xhr.setRequestHeader('transfer-encoding', 'chunked');
  xhr.send();

  function processBuffer_() {
    buffer.split(/\n/).forEach(line => {
      if (itemsMeta === null) {
        itemsMeta = JSON.parse(line);
        processItemsMeta_();
        return;
      }

      if (line) {
        imageBlobs.push(line);
      }
    });

    processImageBlobs_();
  }

  function processItemsMeta_() {
    itemsMeta.forEach(itemMeta => {
      let date = new Date(itemMeta.publish_date);
      let $dayCell = dayNode_(date);
      $dayCell.addClass('loaded');
      $dayCell.find('.meta')
          .empty()
          .css('background-color', itemMeta.color)
          .append($('<span>').addClass('title').text(itemMeta.title))
          .append($('<span>').addClass('byline').text(itemMeta.byline));
      $dayCell.find('.overlay-link').attr('href', itemMeta.details_url);
    });
  }

  function processImageBlobs_() {
    for (let i = imageBlobsProcessed; i < imageBlobs.length; i++) {
      let itemMeta = itemsMeta[i];
      let date = new Date(itemMeta.publish_date);
      let $dayCell = dayNode_(date);
      $dayCell.find('.image').css('background-image', `url(${imageBlobs[i]})`);
    }

    imageBlobsProcessed = imageBlobs.length;
  }

  function processMoreResponseText_() {
    let i = xhr.responseText.lastIndexOf('\n');
    if (i > index) {
      i += 1; // newline
      let newChunk = xhr.responseText.substr(index, (i - index));
      buffer += newChunk;
      index = i;
      processBuffer_();
      buffer = '';
    }
  }
}


new ArchiveController();
