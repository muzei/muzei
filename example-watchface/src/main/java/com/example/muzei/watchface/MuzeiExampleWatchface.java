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

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.Observer;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Size;
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
            implements LifecycleOwner, Observer<Bitmap> {
        private LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
        private Paint mBackgroundPaint;
        private ArtworkImageLoader mLoader;
        private Bitmap mImage;

        @NonNull
        @Override
        public Lifecycle getLifecycle() {
            return lifecycleRegistry;
        }

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
            mLoader = ArtworkImageLoader.getInstance(MuzeiExampleWatchface.this);
            mLoader.observe(this, this);
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START);
        }

        @Override
        public void onChanged(@Nullable final Bitmap bitmap) {
            mImage = bitmap;
            invalidate();
        }

        @Override
        public void onSurfaceChanged(final SurfaceHolder holder, final int format, final int width, final int height) {
            super.onSurfaceChanged(holder, format, width, height);
            mLoader.setRequestedSize(new Size(width, height));
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
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
        }
    }
}
