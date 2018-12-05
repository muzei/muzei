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
import androidx.core.animation.doOnEnd
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.util.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DemoRenderController(
        context: Context,
        renderer: MuzeiBlurRenderer,
        callbacks: RenderController.Callbacks,
        private val allowFocus: Boolean
) : RenderController(context, renderer, callbacks) {

    companion object {
        private const val ANIMATION_CYCLE_TIME_MILLIS = 35000L
        private const val FOCUS_DELAY_TIME_MILLIS = 2000L
        private const val FOCUS_TIME_MILLIS = 6000L
    }

    private lateinit var coroutineScope: CoroutineScope

    private var currentScrollAnimator: Animator? = null
    private var reverseDirection = false

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
            coroutineScope.launch {
                delay(FOCUS_DELAY_TIME_MILLIS)
                renderer.setIsBlurred(false, false)
                delay(FOCUS_TIME_MILLIS)
                renderer.setIsBlurred(true, false)
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        coroutineScope = owner.coroutineScope
        runAnimation()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        currentScrollAnimator?.cancel()
        currentScrollAnimator?.removeAllListeners()
    }

    override suspend fun openDownloadedCurrentArtwork() =
            AssetImageLoader(context.assets, "starrynight.jpg")
}
