const browserify = require('browserify');
const browserSync = require('browser-sync');
const buffer = require('vinyl-buffer');
const del = require('del');
const fs = require('fs');
const history = require('connect-history-api-fallback');
const merge = require('merge-stream');
const path = require('path');
const requireDir = require('require-dir');
const runSequence = require('run-sequence');
const recursiveReaddirSync = require('recursive-readdir-sync');
const source = require('vinyl-source-stream');
const swig = require('swig');
const swigExtras = require('swig-extras');
const through = require('through2');
const url = require('url');
const yaml = require('js-yaml');

const gulp = require('gulp');
const $ = require('gulp-load-plugins')();
const exclude = require('gulp-ignore').exclude;

const DEV_MODE = false;

const AUTOPREFIXER_BROWSERS = [
  'ie >= 10',
  'ie_mob >= 10',
  'ff >= 30',
  'chrome >= 34',
  'safari >= 7',
  'opera >= 23',
  'ios >= 7',
  'android >= 4.4',
  'bb >= 10'
];

function errorHandler(error) {
  console.error(error.stack);
  this.emit('end'); // http://stackoverflow.com/questions/23971388
}


gulp.task('scripts', () => {
  let bundles = [];
  let basePath = 'frontend/scripts';

  recursiveReaddirSync(basePath).forEach(filePath => {
    let filename = path.basename(filePath);
    let dirname = path.dirname(filePath);
    if (/^[^_].+.js$/.test(filename)) {
      bundles.push({filename, dirname});
    }
  });

  let streams = bundles.map(({filename, dirname}) =>
      browserify(`${dirname}/${filename}`, {
            debug: true, // debug generates sourcemap
            basedir: '.',
            paths: [
              './scripts/',
              './node_modules/'
            ]
          })
          .transform('babelify', {presets: ['es2015'], plugins: ['es6-promise']})
          .transform('require-globify')
          .bundle()
          .on('error', errorHandler)
          .pipe(source(filename))
          .pipe(buffer())
          .pipe(gulp.dest(`.tmp/${dirname}`))
          .pipe($.if(!DEV_MODE, $.uglify({
            mangle:false
          })))
          .pipe(gulp.dest(`dist/${dirname}`)));

  return merge(streams);
});


gulp.task('copy', cb => {
  try { fs.mkdirSync('./dist'); } catch (e) {}
  try { fs.symlinkSync('../app.yaml', './dist/app.yaml', 'file'); } catch (e) {}
  try { fs.symlinkSync('../cron.yaml', './dist/cron.yaml', 'file'); } catch (e) {}
  try { fs.symlinkSync('../handlers', './dist/handlers', 'dir'); } catch (e) {}
  try { fs.symlinkSync('../lib', './dist/lib', 'dir'); } catch (e) {}

  let streams = [];

  let copyConfig = {
    '.': [
      '*.py',
    ],
    'frontend': '*.{ico,png}',
    'frontend/lib': '**/*.js'
  };

  for (let base in copyConfig) {
    let paths = copyConfig[base];
    if (!Array.isArray(paths)) {
      paths = [paths];
    }

    streams.push(gulp.src(paths.map(path => base + '/' + path), {dot: true})
      .pipe(gulp.dest('dist' + '/' + base)));
  }

  return merge(streams);
});


gulp.task('styles', () =>
  gulp.src([
    'frontend/styles/*.{scss,css}',
  ])
    .pipe($.changed('styles', {extension: '.scss'}))
    .pipe($.sassGlob())
    .pipe($.sass({
      style: 'expanded',
      precision: 10,
      quiet: true
    }).on('error', errorHandler))
    .pipe($.autoprefixer(AUTOPREFIXER_BROWSERS))
    .pipe(gulp.dest('.tmp/frontend/styles'))
    .pipe($.if('*.css', $.csso()))
    .pipe(gulp.dest('dist/frontend/styles')));


gulp.task('images', () =>
  gulp.src('frontend/images/**/*')
    .pipe($.cache($.imagemin({
      progressive: true,
      interlaced: true,
      svgoPlugins: [{removeTitle: true}],
    })))
    .pipe(gulp.dest('dist/frontend/images')));


gulp.task('html', () => {
  let pages = [];

  return gulp.src([
      'frontend/html/**/*.html',
      '!frontend/html/**/_*.html'
    ])
    // Extract frontmatter
    .pipe($.frontMatter({
      property: 'frontMatter',
      remove: true
    }))
    // Start populating context data for the file, globalData, followed by file's frontmatter
    .pipe($.tap(function(file, t) {
      file.contextData = Object.assign({}, file.frontMatter);
    }))
    // Populate the global pages collection
    // Wait for all files first (to collect all front matter)
    .pipe($.util.buffer())
    .pipe(through.obj(function(filesArray, enc, callback) {
      var me = this;
      filesArray.forEach(function(file) {
        var pageInfo = {path: file.path, data: file.frontMatter || {}};
        pages.push(pageInfo);
      });
      // Re-emit each file into the stream
      filesArray.forEach(function(file) {
        me.push(file);
      });
      callback();
    }))
    .pipe($.tap(function(file, t) {
      // Finally, add pages array to collection
      file.contextData = Object.assign(file.contextData, {all_pages: pages});
    }))
    // Run everything through swig templates
    .pipe($.swig({
      setup: function(sw) {
        swigExtras.useTag(sw, 'markdown');
        swigExtras.useFilter(sw, 'markdown');
        swigExtras.useFilter(sw, 'trim');
      },
      data: function(file) {
        return file.contextData;
      },
      defaults: {
        cache: false
      }
    }))
    // Concatenate And Minify JavaScript
    .pipe($.if('*.js', $.uglify({preserveComments: 'some'})))
    // Concatenate And Minify Styles
    // In case you are still using useref build blocks
    .pipe($.if('*.css', $.csso()))
    // Minify Any HTML
    .pipe(gulp.dest('.tmp/frontend/html'))
    .pipe($.if('*.html', $.minifyHtml()))
    // Output Files
    .pipe(gulp.dest('dist/frontend/html'));
});


gulp.task('clean', cb => {
  del.sync(['.tmp', 'dist']);
  $.cache.clearAll();
  cb();
});


gulp.task('serve', ['build'], () => {
  browserSync({
    notify: false,
    server: {
      baseDir: ['.tmp', '.'],
      serveStaticOptions: {
        extensions: ['html']
      },
      index: '.tmp/frontend/html/landing.html',
      routes: {
        '/archive': '.tmp/frontend/html/archive.html',
        '/today': '.tmp/frontend/html/today.html',
      },
      middleware: [
        {
          route: '/archive',
          handle: history({index: '/'})
        }
      ]
    }
  });

  gulp.watch(['frontend/html/**/*.html'], ['html', browserSync.reload]);
  gulp.watch(['frontend/styles/**/*.{scss,css}'], ['styles', browserSync.reload]);
  gulp.watch(['frontend/scripts/**/*.{js,json}'], ['scripts', browserSync.reload]);
  gulp.watch(['frontend/images/**/*'], browserSync.reload);
});


gulp.task('serve:dist', ['default'], () => {
  browserSync({
    notify: false,
    server: {
      baseDir: ['dist'],
      serveStaticOptions: {
        extensions: ['html']
      },
      index: 'frontend/html/landing.html'
    }
  });
});


gulp.task('build', cb => {
  runSequence('styles', ['html', 'scripts', 'copy', 'images'], cb);
});


gulp.task('default', ['clean', 'build']);
