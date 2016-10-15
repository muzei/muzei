/*
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

package com.example.muzei.watchface;

import android.content.Loader;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.Gravity;
import android.view.SurfaceHolder;

/**
 * Simple watchface example which loads and displays Muzei images as the background
 */
public class MuzeiExampleWatchface extends CanvasWatchFaceService {

    @Override
    public CanvasWatchFaceService.Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine
            implements Loader.OnLoadCompleteListener<Bitmap> {
        private Paint mBackgroundPaint;
        private WatchfaceArtworkImageLoader mLoader;
        private Bitmap mImage;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setWatchFaceStyle(new WatchFaceStyle.Builder(MuzeiExampleWatchface.this)
                    .setStatusBarGravity(Gravity.TOP | Gravity.END)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR
                            | WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setShowSystemUiTime(true)
                    .build());
            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(ContextCompat.getColor(MuzeiExampleWatchface.this, android.R.color.black));
            mLoader = new WatchfaceArtworkImageLoader(MuzeiExampleWatchface.this);
            mLoader.registerListener(0, this);
            mLoader.startLoading();
        }

        @Override
        public void onLoadComplete(Loader<Bitmap> loader, Bitmap image) {
            mImage = image;
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            int width = canvas.getWidth();
            int height = canvas.getHeight();
            if (isInAmbientMode() || mImage == null) {
                canvas.drawRect(0, 0, width, height, mBackgroundPaint);
            } else {
                canvas.drawBitmap(mImage, (width - mImage.getWidth()) / 2,
                        (height - mImage.getHeight()) / 2, null);
            }
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            invalidate();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            if (mLoader != null) {
                mLoader.unregisterListener(this);
                mLoader.reset();
                mLoader = null;
            }
        }
    }
}
