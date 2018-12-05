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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.SystemProviders
import android.support.wearable.complications.rendering.ComplicationDrawable
import android.support.wearable.watchface.CanvasWatchFaceService
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.Observer

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
        private lateinit var complicationDrawable: ComplicationDrawable
        private var image: Bitmap? = null

        override fun getLifecycle(): Lifecycle {
            return lifecycleRegistry
        }

        override fun onCreate(holder: SurfaceHolder) {
            super.onCreate(holder)
            setWatchFaceStyle(WatchFaceStyle.Builder(this@MuzeiExampleWatchface)
                    .setStatusBarGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    .setViewProtectionMode(WatchFaceStyle.PROTECT_STATUS_BAR or WatchFaceStyle.PROTECT_HOTWORD_INDICATOR)
                    .build())
            setDefaultSystemComplicationProvider(0, SystemProviders.TIME_AND_DATE,
                    ComplicationData.TYPE_SHORT_TEXT)
            setActiveComplications(0)
            complicationDrawable = ComplicationDrawable(this@MuzeiExampleWatchface).apply {
                setBorderStyleActive(ComplicationDrawable.BORDER_STYLE_NONE)
                setBorderStyleAmbient(ComplicationDrawable.BORDER_STYLE_NONE)
                setTitleSizeActive(resources.getDimensionPixelSize(R.dimen.title_size))
                setTextSizeActive(resources.getDimensionPixelSize(R.dimen.text_size))
                setTitleSizeAmbient(resources.getDimensionPixelSize(R.dimen.title_size))
                setTextSizeAmbient(resources.getDimensionPixelSize(R.dimen.text_size))
            }
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
            complicationDrawable.setBounds(0, 0, width, height)
        }

        override fun onComplicationDataUpdate(watchFaceComplicationId: Int, data: ComplicationData?) {
            complicationDrawable.setComplicationData(data)
        }

        override fun onDraw(canvas: Canvas, bounds: Rect) {
            image?.takeUnless { isInAmbientMode }?.let { image ->
                canvas.drawBitmap(image, ((canvas.width - image.width) / 2).toFloat(),
                        ((canvas.height - image.height) / 2).toFloat(), null)
            } ?: run {
                canvas.drawRect(bounds, backgroundPaint)
            }
            complicationDrawable.draw(canvas)
        }

        override fun onAmbientModeChanged(inAmbientMode: Boolean) {
            complicationDrawable.setInAmbientMode(inAmbientMode)
            invalidate()
        }

        override fun onPropertiesChanged(properties: Bundle) {
            complicationDrawable.setLowBitAmbient(
                    properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false))
            complicationDrawable.setBurnInProtection(
                    properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false))
            invalidate()
        }

        override fun onDestroy() {
            super.onDestroy()
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }
}
