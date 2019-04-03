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

package com.google.android.apps.muzei.util

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.fragment.app.Fragment
import net.nurik.roman.muzei.R

class AnimatedMuzeiLogoFragment : Fragment(R.layout.animated_logo_fragment) {
    private lateinit var subtitleView: View
    private lateinit var logoView: AnimatedMuzeiLogoView
    private val initialLogoOffset: Float by lazy {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f,
                resources.displayMetrics)
    }
    var onFillStarted: () -> Unit = {}

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        subtitleView = view.findViewById(R.id.logo_subtitle)

        logoView = view.findViewById(R.id.animated_logo)
        logoView.onStateChange = { state ->
            if (state == AnimatedMuzeiLogoView.STATE_FILL_STARTED) {
                subtitleView.apply {
                    alpha = 0f
                    visibility = View.VISIBLE
                    translationY = (-height).toFloat()
                }

                // Bug in older versions where set.setInterpolator didn't work
                val interpolator = OvershootInterpolator()
                val a1 = ObjectAnimator.ofFloat<View>(logoView, View.TRANSLATION_Y, 0f)
                val a2 = ObjectAnimator.ofFloat<View>(subtitleView,
                        View.TRANSLATION_Y, 0f)
                val a3 = ObjectAnimator.ofFloat<View>(subtitleView, View.ALPHA, 1f)
                a1.interpolator = interpolator
                a2.interpolator = interpolator
                AnimatorSet().apply {
                    setDuration(500).playTogether(a1, a2, a3)
                    start()
                }

                onFillStarted.invoke()
            }
        }
        if (savedInstanceState == null) {
            reset()
        }
    }

    fun start() {
        logoView.start()
    }

    private fun reset() {
        logoView.reset()
        logoView.translationY = initialLogoOffset
        subtitleView.visibility = View.INVISIBLE
    }
}
