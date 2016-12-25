if (Modernizr.cssanimations &&
    Modernizr.svg &&
    Modernizr.csstransforms3d &&
    Modernizr.csstransitions) {

  // progressive enhancement
  $('#device-screen').css('display', 'block');
  $('#device-frame').addClass('frameonly');
  $('#opentarget').css('visibility', 'visible');
  $('body').addClass('withvignette');

  $('#opentarget, #layer-muzei-icon')
      .click(() => {
        setTimeout(() => $('#device-frame').toggleClass('artdetailopen'), 0);
      });

  $(document)
      .on('mousedown', '#opentarget, #layer-muzei-icon', () => {
        $('#device-frame').addClass('mousedown');
      })
      .on('mouseup', () => {
        $('#device-frame').removeClass('mousedown');
      });

  $('#device-frame').click(function() {
    if (!$(this).hasClass('artdetailopen')) {
      return;
    }

    $(this).toggleClass('artdetailopen');
  });

  $.ajax({
    dataType: 'jsonp',
    url: 'http://muzei.co/featured',
    jsonpCallback: 'withfeatured',
    cache: true,
    success: data => {
      let image = data.thumbUri || data.imageUri;
      $('#layer-wall, #layer-wall-blurred').css('background-image', `url('${image}')`);
      $('#layer-artdetail .title').text(data.title);
      $('#layer-artdetail .byline').text(data.byline);
    }
  });
}

// Hide additional target for Android to prevent disambig from showing up
if (navigator.userAgent.indexOf('Android') >= 0) {
  $('#opentarget').hide();
}
