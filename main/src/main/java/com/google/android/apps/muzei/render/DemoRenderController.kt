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

import android.animation.Animator
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Handler
import androidx.core.animation.doOnEnd

class DemoRenderController(
        context: Context, renderer: MuzeiBlurRenderer,
        callbacks: RenderController.Callbacks,
        private val allowFocus: Boolean
) : RenderController(context, renderer, callbacks) {

    companion object {
        private const val ANIMATION_CYCLE_TIME_MILLIS = 35000L
        private const val FOCUS_DELAY_TIME_MILLIS = 2000L
        private const val FOCUS_TIME_MILLIS = 6000L
    }

    private val handler = Handler()

    private var currentScrollAnimator: Animator? = null
    private var reverseDirection = false

    init {
        runAnimation()
    }

    private fun runAnimation() {
        currentScrollAnimator?.cancel()

        currentScrollAnimator = ObjectAnimator
                .ofFloat(renderer, "normalOffsetX",
                        if (reverseDirection) 1f else 0f, if (reverseDirection) 0f else 1f).apply {

                    duration = ANIMATION_CYCLE_TIME_MILLIS
                    doOnEnd {
                        reverseDirection = !reverseDirection
                        runAnimation()
                    }
                    start()
                }
        if (allowFocus) {
            handler.postDelayed({
                renderer.setIsBlurred(false, false)
                handler.postDelayed({ renderer.setIsBlurred(true, false) }, FOCUS_TIME_MILLIS)
            }, FOCUS_DELAY_TIME_MILLIS)
        }
    }

    override fun destroy() {
        super.destroy()
        currentScrollAnimator?.cancel()
        currentScrollAnimator?.removeAllListeners()
        handler.removeCallbacksAndMessages(null)
    }

    override suspend fun openDownloadedCurrentArtwork(forceReload: Boolean) =
            BitmapRegionLoader.newInstance(context.assets.open("starrynight.jpg"))
}
