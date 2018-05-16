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

package com.google.android.apps.muzei.render

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.util.Log
import com.google.android.apps.muzei.settings.Prefs
import kotlinx.coroutines.experimental.android.UI
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.launch

abstract class RenderController(
        protected var context: Context,
        protected var renderer: MuzeiBlurRenderer,
        protected var callbacks: Callbacks
) {

    companion object {
        private const val TAG = "RenderController"
    }

    var visible: Boolean = false
        set(value) {
            field = value
            if (value) {
                callbacks.queueEventOnGlThread {
                    val loader = queuedBitmapRegionLoader
                    if (loader != null) {
                        renderer.setAndConsumeBitmapRegionLoader(loader)
                        queuedBitmapRegionLoader = null
                    }
                }
                callbacks.requestRender()
            }
        }
    private var destroyed = false
    private var queuedBitmapRegionLoader: BitmapRegionLoader? = null
    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.PREF_BLUR_AMOUNT -> {
                renderer.recomputeMaxPrescaledBlurPixels()
                throttledForceReloadCurrentArtwork()
            }
            Prefs.PREF_DIM_AMOUNT -> {
                renderer.recomputeMaxDimAmount()
                throttledForceReloadCurrentArtwork()
            }
            Prefs.PREF_GREY_AMOUNT -> {
                renderer.recomputeGreyAmount()
                throttledForceReloadCurrentArtwork()
            }
        }
    }

    private val throttledForceReloadHandler by lazy {
        Handler(Handler.Callback {
            reloadCurrentArtwork(true)
            true
        })
    }

    init {
        Prefs.getSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    open fun destroy() {
        queuedBitmapRegionLoader?.close()
        queuedBitmapRegionLoader = null
        Prefs.getSharedPreferences(context)
                .unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        destroyed = true
    }

    private fun throttledForceReloadCurrentArtwork() {
        throttledForceReloadHandler.removeMessages(0)
        throttledForceReloadHandler.sendEmptyMessageDelayed(0, 250)
    }

    protected abstract suspend fun openDownloadedCurrentArtwork(
            forceReload: Boolean
    ): BitmapRegionLoader?

    fun reloadCurrentArtwork(forceReload: Boolean) {
        if (destroyed) {
            // Don't reload artwork for destroyed RenderControllers
            return
        }
        launch(UI) {
            val bitmapRegionLoader = async {
                openDownloadedCurrentArtwork(forceReload)
            }.await()
            if (bitmapRegionLoader == null || bitmapRegionLoader.width == 0 ||
                    bitmapRegionLoader.height == 0) {
                Log.w(TAG, "Could not open the current artwork")
                bitmapRegionLoader?.close()
                return@launch
            }

            callbacks.queueEventOnGlThread {
                if (visible) {
                    renderer.setAndConsumeBitmapRegionLoader(bitmapRegionLoader)
                } else {
                    queuedBitmapRegionLoader?.close()
                    queuedBitmapRegionLoader = bitmapRegionLoader
                }
            }
        }
    }

    interface Callbacks {
        fun queueEventOnGlThread(event: () -> Unit)
        fun requestRender()
    }
}
