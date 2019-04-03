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

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

class IntroFragment : Fragment(R.layout.intro_fragment) {

    private lateinit var activateButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            FirebaseAnalytics.getInstance(requireContext()).logEvent(FirebaseAnalytics.Event.TUTORIAL_BEGIN, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        activateButton = view.findViewById(R.id.activate_muzei_button)
        activateButton.setOnClickListener {
            FirebaseAnalytics.getInstance(requireContext()).logEvent("activate", null)
            try {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(requireContext(),
                                        MuzeiWallpaperService::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                try {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: ActivityNotFoundException) {
                    requireContext().toast(R.string.error_wallpaper_chooser, Toast.LENGTH_LONG)
                }
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState == null) {
            val logoFragment = AnimatedMuzeiLogoFragment()
            childFragmentManager.commitNow {
                add(R.id.animated_logo_fragment, logoFragment)
            }

            activateButton.alpha = 0f
            logoFragment.onFillStarted = {
                activateButton.animate().alpha(1f).setDuration(500)
            }
            activateButton.postDelayed({
                if (logoFragment.isAdded) {
                    logoFragment.start()
                }
            }, 1000)
        }
    }
}

class IntroButton : Button {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, attributeSetId: Int) : super(context, attrs, attributeSetId)
}
