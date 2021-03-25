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
import android.icu.util.Calendar
import android.support.wearable.watchface.WatchFaceStyle
import android.util.Size
import android.view.Gravity
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.wear.complications.DefaultComplicationProviderPolicy
import androidx.wear.complications.SystemProviders
import androidx.wear.complications.data.ComplicationType
import androidx.wear.watchface.CanvasComplicationDrawable
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.Complication
import androidx.wear.watchface.ComplicationsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.complications.rendering.ComplicationDrawable
import androidx.wear.watchface.style.UserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Simple watchface example which loads and displays Muzei images as the background
 */
class MuzeiExampleWatchface : WatchFaceService() {

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState
    ): WatchFace {
        // This example uses a single, fixed complication to show the time and date
        // rather than manually draw the time
        val timeComplication = Complication.createBackgroundComplicationBuilder(
            0,
            CanvasComplicationDrawable(ComplicationDrawable(this).apply {
                activeStyle.run {
                    titleSize = resources.getDimensionPixelSize(R.dimen.title_size)
                    textSize = resources.getDimensionPixelSize(R.dimen.text_size)
                }
                ambientStyle.run {
                    titleSize = resources.getDimensionPixelSize(R.dimen.title_size)
                    textSize = resources.getDimensionPixelSize(R.dimen.text_size)
                }
            }, watchState),
            listOf(ComplicationType.SHORT_TEXT),
            DefaultComplicationProviderPolicy(SystemProviders.TIME_AND_DATE)
        ).setFixedComplicationProvider(true).build()

        // Now build the components needed for the WatchFace
        val userStyleRepository = UserStyleRepository(
            UserStyleSchema(listOf())
        )
        val complicationsManager = ComplicationsManager(
            listOf(timeComplication),
            userStyleRepository
        )
        val renderer = MuzeiExampleRenderer(
            surfaceHolder,
            userStyleRepository,
            watchState,
            complicationsManager
        )
        return WatchFace(
            WatchFaceType.DIGITAL,
            userStyleRepository,
            renderer,
            complicationsManager
        ).setLegacyWatchFaceStyle(
            WatchFace.LegacyWatchFaceOverlayStyle(
                WatchFaceStyle.PROTECT_STATUS_BAR or
                        WatchFaceStyle.PROTECT_HOTWORD_INDICATOR,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                false)
        )
    }

    private inner class MuzeiExampleRenderer(
        surfaceHolder: SurfaceHolder,
        userStyleRepository: UserStyleRepository,
        watchState: WatchState,
        private val complicationsManager: ComplicationsManager
    ) : Renderer.CanvasRenderer(
        surfaceHolder,
        userStyleRepository,
        watchState,
        CanvasType.HARDWARE,
        32 // as a ~static watchface, we don't need 60fps
    ) {
        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val backgroundPaint: Paint = Paint().apply {
            color = ContextCompat.getColor(this@MuzeiExampleWatchface, android.R.color.black)
        }
        private val loader = ArtworkImageLoader(this@MuzeiExampleWatchface)
        private var image: Bitmap? = null

        init {
            coroutineScope.launch {
                loader.artworkFlow.collect { bitmap ->
                    image = bitmap
                    invalidate()
                }
            }
        }

        override fun render(canvas: Canvas, bounds: Rect, calendar: Calendar) {
            loader.requestedSize = Size(canvas.width, canvas.height)
            val isInAmbientMode = renderParameters.drawMode == DrawMode.AMBIENT
            image?.takeUnless { isInAmbientMode }?.let { image ->
                canvas.drawBitmap(image, ((canvas.width - image.width) / 2).toFloat(),
                        ((canvas.height - image.height) / 2).toFloat(), null)
            } ?: run {
                canvas.drawRect(bounds, backgroundPaint)
            }
            complicationsManager[0]?.render(canvas, calendar, renderParameters)
        }

        override fun onDestroy() {
            super.onDestroy()
            coroutineScope.cancel()
        }
    }
}
