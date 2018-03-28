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

package com.example.muzei.watchface

import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LifecycleRegistry
import android.arch.lifecycle.Observer
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.support.v4.content.ContextCompat
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder

/**
 * Simple watchface example which loads and displays Muzei images as the background
 */
class MuzeiExampleWatchface : CanvasWatchFaceService() {

    override fun onCreateEngine(): CanvasWatchFaceService.Engine {
        return Engine()
    }

    private inner class Engine : CanvasWatchFaceService.Engine(), LifecycleOwner, Observer<Bitmap> {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val backgroundPaint: Paint = Paint().apply {
            color = ContextCompat.getColor(this@MuzeiExampleWatchface, android.R.color.black)
        }
        private val loader: ArtworkImageLoader = ArtworkImageLoader.getInstance(this@MuzeiExampleWatchface)
        private var image: Bitmap? = null

        override fun getLifecycle(): Lifecycle {
            return lifecycleRegistry
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setWatchFaceStyle(WatchFaceStyle.Builder(this@MuzeiExampleWatchface)
                    .setStatusBarGravity(Gravity.TOP or Gravity.END)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR or WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .setPeekOpacityMode(WatchFaceStyle.PEEK_OPACITY_MODE_TRANSLUCENT)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setShowSystemUiTime(true)
                    .build())
            loader.observe(this, this)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        override fun onChanged(bitmap: Bitmap?) {
            image = bitmap
            invalidate()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            loader.requestedSize = Size(width, height)
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            image?.takeUnless { isInAmbientMode }?.let { image ->
                canvas.drawBitmap(image, ((canvas.width - image.width) / 2).toFloat(),
                        ((canvas.height - image.height) / 2).toFloat(), null)
            } ?: run {
                canvas.drawRect(bounds, backgroundPaint)
            }
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            invalidate()
        }

        override fun onDestroy() {
            super.onDestroy()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
