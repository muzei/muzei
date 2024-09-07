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
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commitNow
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment
import com.google.android.apps.muzei.util.autoCleared
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.IntroFragmentBinding

class IntroFragment : Fragment(R.layout.intro_fragment) {

    private var binding: IntroFragmentBinding by autoCleared()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            Firebase.analytics.logEvent(FirebaseAnalytics.Event.TUTORIAL_BEGIN, null)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = IntroFragmentBinding.bind(view)
        binding.activateMuzei.setOnClickListener {
            Firebase.analytics.logEvent("activate", null)
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
        if (binding.animatedLogoFragment.getFragment<Fragment?>() == null) {
            val logoFragment = AnimatedMuzeiLogoFragment()
            childFragmentManager.commitNow {
                setReorderingAllowed(true)
                add(R.id.animated_logo_fragment, logoFragment)
            }

            binding.activateMuzei.alpha = 0f
            logoFragment.onFillStarted = {
                binding.activateMuzei.animate().alpha(1f).apply {
                    duration = 500
                }
            }
            binding.activateMuzei.postDelayed({
                if (logoFragment.isAdded) {
                    logoFragment.start()
                }
            }, 1000)
        }
    }
}