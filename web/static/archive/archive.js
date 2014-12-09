/**
 * Copyright 2014 Google Inc.
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

/*
 * Carousel
 */

var MONTHS = [
    'January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
    'September', 'October', 'November', 'December'];
var DAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
var DAY_MILLIS = 24 * 60 * 60 * 1000;

var START_DATE = new Date('2014-02-12');
var START_MONTH = [START_DATE.getFullYear(), START_DATE.getMonth() + 1];
var TODAY = new Date();
var TODAY_MONTH = [TODAY.getFullYear(), TODAY.getMonth() + 1];
var NUM_CAROUSEL_PAGES = monthDistance(START_MONTH, TODAY_MONTH) + 1;

var MONTH_SPACING_WIDE = 80; // pixels
var MONTH_SPACING_NARROW = 40; // pixels

var ARCHIVE_BASE_URL = 'http://storage.googleapis.com/muzeifeaturedart/archivemeta/';


var $carousel = $('.month-carousel');
var pageWidth = 0;
var selectedMonth;


(function() {
  var m = document.location.href.match(/\/archive\/(.+)/);
  if (m && isMonthInValidRange(monthFromUrlPath(m[1]))) {
    selectMonth(monthFromUrlPath(m[1])); //document.location.hash.substring(1)));
  } else {
    selectMonth(TODAY_MONTH);
  }

  // $(window).on('hashchange', function(e) {
  //   selectMonth(monthFromKey(document.location.hash.substring(1)));
  //   e.preventDefault();
  //   return false;
  // });
})();

$(window).resize(function() {
  var newPageWidth = $carousel.width();
  if (pageWidth != newPageWidth) {
    pageWidth = newPageWidth;
    relayout();
  }
});

$(document)
    .on('click', '.month .click-screen', function() {
      selectMonth(monthFromId($(this).parent().attr('id')));
    })
    .on('keydown', function(e) {
      if (e.keyCode == 37) {
        selectMonth(prevMonth(selectedMonth));
      } else if (e.keyCode == 39) {
        selectMonth(nextMonth(selectedMonth));
      }
    });

function renderMonth(month, force) {
  if (!isMonthInValidRange(month)) {
    return;
  }

  if (!pageWidth) {
    pageWidth = $carousel.width();
  }

  var $month = monthNode(month);
  var needsRender = !!force;
  if (!$month.length) {
    $month = $('<div>')
        .css('left', monthX(month) + 'px')
        .addClass('month')
        .attr('id', 'month-' + monthKey(month))
        .appendTo($carousel);
    needsRender = true;
  }
  if (needsRender) {
    $month.empty();
    renderMonthContents(month);
  }
  return $month;
}

function relayout() {
  $('.month').each(function() {
    var month = monthFromId($(this).attr('id'));
    $(this).css('left', monthX(month) + 'px');
  });
  selectMonth(selectedMonth);
}

function selectMonth(month) {
  if (!isMonthInValidRange(month)) {
    selectMonth(selectedMonth);
    return;
  }

  renderMonth(month);
  $month = monthNode(month);
  $carousel.addClass('animate');
  $carousel.css({
    'transform': 'translate3d(' + -($month.offset().left - $carousel.offset().left) + 'px,0,0)'
  });
  $('.month').removeClass('active');
  $month.toggleClass('active', true);
  selectedMonth = month;

  var newUrl = '/archive/' + monthUrlPath(month);
  window.history.replaceState('', '', newUrl);
  //document.location.replace('#' + monthKey(month));
  renderMonth(prevMonth(month));
  renderMonth(nextMonth(month));
}

function clearMonth(month) {
  monthNode(month).empty();
}

function isMonthInValidRange(month) {
  return monthDistance(START_MONTH, month) >= 0 &&
         monthDistance(month, TODAY_MONTH) >= 0;
}

function monthX(month) {
  var monthSpacing = (pageWidth <= 320) ? MONTH_SPACING_NARROW : MONTH_SPACING_WIDE;
  return -monthDistance(month, TODAY_MONTH) * (pageWidth + monthSpacing);
}

function monthNode(month) {
  return $('#month-' + monthKey(month));
}

function monthKey(month) {
  return month[0] + (month[1] < 10 ? '0' : '') + month[1];
}

function monthFromKey(key) {
  return [parseInt(key.substring(0, 4), 10),
          parseInt(key.substring(4, 6), 10)];
}

function monthUrlPath(month) {
  return month[0] + '/' + month[1];
}

function monthFromUrlPath(path) {
  var m = path.match(/(\d+)(\/(\d+))?/);
  return [parseInt(m[1], 10),
          parseInt(m[3] || 1, 10)];
}

function monthFromId(id) {
  return [parseInt(id.substring(6, 10), 10),
          parseInt(id.substring(10, 12), 10)];
}

function monthDistance(month1, month2) {
  return (month2[0] - month1[0]) * 12 + (month2[1] - month1[1]);
}

function prevMonth(month) {
  if (month[1] == 1) {
    return [month[0] - 1, 12];
  }

  return [month[0], month[1] - 1];
}

function nextMonth(month) {
  if (month[1] == 12) {
    return [month[0] + 1, 1];
  }

  return [month[0], month[1] + 1];
}

// based on http://rawgit.com/EightMedia/hammer.js/1.1.3/tests/manual/carousel.html

function handleHammer(ev) {
  // disable browser scrolling
  ev.gesture.preventDefault();

  var currentPage = monthDistance(START_MONTH, selectedMonth);

  switch (ev.type) {
    case 'dragright':
    case 'dragleft':
      // stick to the finger
      var monthOffset = monthX(selectedMonth);
      var dragOffset = -ev.gesture.deltaX;

      // slow down at the first and last pane
      if ((currentPage == 0 && ev.gesture.direction == 'right') ||
        (currentPage == NUM_CAROUSEL_PAGES - 1 && ev.gesture.direction == 'left')) {
        dragOffset *= .4;
      }

      var offset = monthOffset + dragOffset;

      $carousel.removeClass('animate');
      $carousel.css({
        'transform': 'translate3d(' + -offset + 'px,0,0)'
      });
      break;

    case 'swipeleft':
      selectMonth(nextMonth(selectedMonth));
      ev.gesture.stopDetect();
      break;

    case 'swiperight':
      selectMonth(prevMonth(selectedMonth));
      ev.gesture.stopDetect();
      break;

    case 'release':
      // more then 50% moved, navigate
      if (Math.abs(ev.gesture.deltaX) > pageWidth / 2) {
        if (ev.gesture.direction == 'right') {
          selectMonth(prevMonth(selectedMonth));
        } else {
          selectMonth(nextMonth(selectedMonth));
        }
      } else {
        selectMonth(selectedMonth);
      }
      break;
  }
}

$(document).ready(function() {
  new Hammer($carousel.get(0), { dragLockToAxis: true })
      .on("release dragleft dragright swipeleft swiperight", handleHammer);
});

/*
 * Month page
 */
function renderMonthContents(month) {
  var $month = monthNode(month);

  $('<div>')
      .addClass('click-screen')
      .appendTo($month);

  // header row
  var $monthHeader = $('<div>')
      .text(MONTHS[month[1] - 1] + ' ' + month[0])
      .addClass('month-row month-header').appendTo($month);

  // weekdays row
  var $weekdayLabelsRow = $('<div>')
      .addClass('month-row weekday-labels')
      .appendTo($month);
  for (var dow = 0; dow < 7; dow++) {
    $('<div>')
        .text(DAYS[dow])
        .addClass('cell')
        .appendTo($weekdayLabelsRow);
  }

  // days
  var today = new Date();
  var todayYear = today.getUTCFullYear();
  var todayMonth = today.getUTCMonth();
  var todayDate = today.getUTCDate();

  var firstOfMonthDate = new Date();
  firstOfMonthDate.setUTCHours(0);
  firstOfMonthDate.setUTCMinutes(0);
  firstOfMonthDate.setUTCSeconds(0);
  firstOfMonthDate.setUTCDate(1);
  firstOfMonthDate.setUTCFullYear(month[0]);
  firstOfMonthDate.setUTCMonth(month[1] - 1);

  var dowFirstOfMonth = firstOfMonthDate.getUTCDay();
  var firstVisibleDate = new Date(firstOfMonthDate - dowFirstOfMonth * DAY_MILLIS);
  var date = firstVisibleDate;
  var week = 0;
  var $daysRow = $('<div>')
      .addClass('month-row days')
      .appendTo($month);

  var monthEnded = true;
  while (++week < 10) {
    for (var dow = 0; dow < 7; dow++) {
      var dayMonth = date.getUTCMonth();
      var dayYear = date.getUTCFullYear();
      var dayDate = date.getUTCDate();
      var skipDay = false;

      var classes = 'cell ';
      if (dayMonth != month[1] - 1) {
        // previous month
        skipDay = true;
        classes += 'skipped ';
        if (dayMonth < month[1] - 1) {
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

      var $dayCell = $('<div>')
          .addClass(classes)
          .appendTo($daysRow);

      if (!skipDay) {
        $dayCell.attr('id', 'day-' + dayKey(date));
      }

      if (!skipDay) {
        // generate day cell innards
        var $image = $('<div>')
            .addClass('image')
            .appendTo($dayCell);

        var $meta = $('<div>')
            .addClass('meta')
            .appendTo($dayCell);

        // show overlay and layer
        var $overlay = $('<a>')
            .addClass('overlay-link')
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
  var archiveKey = dayKey(today);
  if (monthEnded) {
    archiveKey = monthKey(month);
  }

  loadArchiveData(archiveKey);
}

// loosely based on http://www.kylescholz.com/blog/2010/01/progressive_xmlhttprequest_1.html
function loadArchiveData(archiveKey) {
  var xhr = new XMLHttpRequest();
  var buffer = '';
  var index = 0;

  var itemsMeta = null;
  var imageBlobs = [];
  var imageBlobsProcessed = 0;

  var _processBuffer = function() {
    lines = buffer.split(/\n/);
    for (var l = 0; l < lines.length; l++) {
      var line = lines[l];
      if (itemsMeta === null) {
        itemsMeta = JSON.parse(line);
        _processItemsMeta();
        continue;
      }

      if (line) {
        imageBlobs.push(line);
      }
    }

    _processImageBlobs();
  };

  var _processItemsMeta = function() {
    for (var i = 0; i < itemsMeta.length; i++) {
      var itemMeta = itemsMeta[i];
      var date = new Date(itemMeta.publish_date);
      $dayCell = dayNode(date);
      $dayCell.find('.meta').text(itemMeta.byline);
      $dayCell.find('.overlay-link').attr('href', itemMeta.details_url);
    }
  };

  var _processImageBlobs = function() {
    for (var i = imageBlobsProcessed; i < imageBlobs.length; i++) {
      var itemMeta = itemsMeta[i];
      var date = new Date(itemMeta.publish_date);
      $dayCell = dayNode(date);
      $dayCell.find('.image').css('background-image', 'url(' + imageBlobs[i] + ')');
    }

    imageBlobsProcessed = imageBlobs.length;
  };

  var _processMoreResponseText = function() {
    var i = xhr.responseText.lastIndexOf('\n');
    if (i > index) {
      i += 1; // newline
      var newChunk = xhr.responseText.substr(index, (i - index));
      buffer += newChunk;
      index = i;
      _processBuffer();
      buffer = '';
    }
  };

  xhr.addEventListener('load', function(e) {
    _processMoreResponseText();
  }, false);

  xhr.addEventListener('progress', function(e) {
    _processMoreResponseText();
  }, false);

  xhr.addEventListener('error', function(e) {
    // todo
  }, false);

  xhr.addEventListener('abort', function(e) {
    // todo
  }, false);

  xhr.open('get', ARCHIVE_BASE_URL + archiveKey + '.txt', true);
  xhr.overrideMimeType('text/plain');
  //xhr.setRequestHeader('transfer-encoding', 'chunked');
  xhr.send();
}

function dayNode(date) {
  return $('#day-' + dayKey(date));
}

function dayKey(date) {
  var y = date.getUTCFullYear();
  var m = date.getUTCMonth() + 1;
  var d = date.getUTCDate();
  return y + (m < 10 ? '0' : '') + m + (d < 10 ? '0' : '') + d;
}