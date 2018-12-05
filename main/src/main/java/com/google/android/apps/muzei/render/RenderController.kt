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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.settings.Prefs
import com.google.android.apps.muzei.util.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

abstract class RenderController(
        protected var context: Context,
        protected var renderer: MuzeiBlurRenderer,
        private var callbacks: Callbacks
) : DefaultLifecycleObserver {

    var visible: Boolean = false
        set(value) {
            field = value
            if (value) {
                callbacks.queueEventOnGlThread {
                    val loader = queuedImageLoader
                    if (loader != null) {
                        queuedImageLoader = null
                        renderer.setAndConsumeImageLoader(loader)
                    }
                }
                callbacks.requestRender()
            }
        }
    var onLockScreen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                renderer.recomputeMaxPrescaledBlurPixels(
                        if (value) Prefs.PREF_LOCK_BLUR_AMOUNT else Prefs.PREF_BLUR_AMOUNT)
                renderer.recomputeMaxDimAmount(
                        if (value) Prefs.PREF_LOCK_DIM_AMOUNT else Prefs.PREF_DIM_AMOUNT)
                renderer.recomputeGreyAmount(
                        if (value) Prefs.PREF_LOCK_GREY_AMOUNT else Prefs.PREF_GREY_AMOUNT)
                reloadCurrentArtwork()
            }
        }
    private lateinit var coroutineScope: CoroutineScope
    private var destroyed = false
    private var queuedImageLoader: ImageLoader? = null
    private val sharedPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (onLockScreen) {
            when (key) {
                Prefs.PREF_LOCK_BLUR_AMOUNT -> {
                    renderer.recomputeMaxPrescaledBlurPixels()
                    throttledForceReloadCurrentArtwork()
                }
                Prefs.PREF_LOCK_DIM_AMOUNT -> {
                    renderer.recomputeMaxDimAmount()
                    throttledForceReloadCurrentArtwork()
                }
                Prefs.PREF_LOCK_GREY_AMOUNT -> {
                    renderer.recomputeGreyAmount()
                    throttledForceReloadCurrentArtwork()
                }
            }
        } else {
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
    }

    private val throttledForceReloadHandler by lazy {
        Handler(Handler.Callback {
            reloadCurrentArtwork()
            true
        })
    }

    override fun onCreate(owner: LifecycleOwner) {
        coroutineScope = owner.coroutineScope
        Prefs.getSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        queuedImageLoader = null
        Prefs.getSharedPreferences(context)
                .unregisterOnSharedPreferenceChangeListener(sharedPreferenceChangeListener)
        destroyed = true
    }

    private fun throttledForceReloadCurrentArtwork() {
        throttledForceReloadHandler.removeMessages(0)
        throttledForceReloadHandler.sendEmptyMessageDelayed(0, 250)
    }

    protected abstract suspend fun openDownloadedCurrentArtwork(): ImageLoader

    fun reloadCurrentArtwork() {
        if (destroyed) {
            // Don't reload artwork for destroyed RenderControllers
            return
        }
        coroutineScope.launch(Dispatchers.Main) {
            val imageLoader = openDownloadedCurrentArtwork()

            callbacks.queueEventOnGlThread {
                if (visible) {
                    renderer.setAndConsumeImageLoader(imageLoader)
                } else {
                    queuedImageLoader = imageLoader
                }
            }
        }
    }

    interface Callbacks {
        fun queueEventOnGlThread(event: () -> Unit)
        fun requestRender()
    }
}
