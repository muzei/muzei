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

package com.google.android.apps.muzei

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.drawable.AnimatedVectorDrawable
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.TypedValue
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import androidx.core.animation.doOnEnd
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

class TutorialFragment : Fragment(R.layout.tutorial_fragment) {

    companion object {
        const val PREF_SEEN_TUTORIAL = "seen_tutorial"
    }

    private val runningAnimators = mutableListOf<AnimatorSet>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        ViewCompat.requestApplyInsets(view)
        view.findViewById<View>(R.id.tutorial_icon_affordance).setOnClickListener {
            FirebaseAnalytics.getInstance(requireContext())
                    .logEvent(FirebaseAnalytics.Event.TUTORIAL_COMPLETE, null)
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putBoolean(PREF_SEEN_TUTORIAL, true)
            }
        }

        if (savedInstanceState == null) {
            val animateDistance = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 100f,
                    resources.displayMetrics)
            val mainTextView = view.findViewById<View>(R.id.tutorial_main_text).apply {
                alpha = 0f
                translationY = -animateDistance / 5
            }
            val subTextView = view.findViewById<View>(R.id.tutorial_sub_text).apply {
                alpha = 0f
                translationY = -animateDistance / 5
            }
            val affordanceView = view.findViewById<View>(R.id.tutorial_icon_affordance).apply {
                alpha = 0f
                translationY = animateDistance
            }
            val iconTextView = view.findViewById<View>(R.id.tutorial_icon_text).apply {
                alpha = 0f
                translationY = animateDistance
            }
            runningAnimators.add(AnimatorSet().apply {
                startDelay = 500
                duration = 250
                playTogether(
                        ObjectAnimator.ofFloat(mainTextView, View.ALPHA, 1f),
                        ObjectAnimator.ofFloat(subTextView, View.ALPHA, 1f))
                doOnEnd { runningAnimators.remove(this) }
                start()
            })
            runningAnimators.add(AnimatorSet().apply {
                startDelay = 2000
                duration = 500
                // Bug in older versions where set.setInterpolator didn't work
                val interpolator = OvershootInterpolator()
                val a1 = ObjectAnimator.ofFloat<View>(affordanceView, View.TRANSLATION_Y, 0f)
                val a2 = ObjectAnimator.ofFloat<View>(iconTextView, View.TRANSLATION_Y, 0f)
                val a3 = ObjectAnimator.ofFloat<View>(mainTextView, View.TRANSLATION_Y, 0f)
                val a4 = ObjectAnimator.ofFloat<View>(subTextView, View.TRANSLATION_Y, 0f)
                a1.interpolator = interpolator
                a2.interpolator = interpolator
                a3.interpolator = interpolator
                a4.interpolator = interpolator
                playTogether(
                        ObjectAnimator.ofFloat(affordanceView, View.ALPHA, 1f),
                        ObjectAnimator.ofFloat(iconTextView, View.ALPHA, 1f),
                        a1, a2, a3, a4)
                doOnEnd {
                    if (isAdded && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val emanateView = view.findViewById<ImageView>(R.id.tutorial_icon_emanate)
                        val avd = ResourcesCompat.getDrawable(resources,
                                R.drawable.avd_tutorial_icon_emanate,
                                context?.theme) as AnimatedVectorDrawable
                        emanateView.setImageDrawable(avd)
                        avd.start()
                    }
                    runningAnimators.remove(this)
                }
                start()
            })
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val emanateView = view.findViewById<ImageView>(R.id.tutorial_icon_emanate)
            val avd = ResourcesCompat.getDrawable(resources,
                    R.drawable.avd_tutorial_icon_emanate,
                    context?.theme) as AnimatedVectorDrawable
            emanateView.setImageDrawable(avd)
            avd.start()
        }
    }

    override fun onDestroyView() {
        runningAnimators.forEach {
            it.removeAllListeners()
            it.cancel()
        }
        super.onDestroyView()
    }
}
