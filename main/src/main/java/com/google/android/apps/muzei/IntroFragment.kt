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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.fragment.app.Fragment
import com.google.android.apps.muzei.util.AnimatedMuzeiLogo
import com.google.android.apps.muzei.util.autoCleared
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import kotlinx.coroutines.delay
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
            } catch (_: ActivityNotFoundException) {
                try {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    requireContext().toast(R.string.error_wallpaper_chooser, Toast.LENGTH_LONG)
                }
            }
        }
        binding.animatedLogo.setContent {
            var started by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(started) {
                if (!started) {
                    binding.activateMuzei.alpha = 0f
                    delay(1000)
                    started = true
                }
            }
            AnimatedMuzeiLogo(
                started = started,
                onFillStarted = {
                    binding.activateMuzei.animate().alpha(1f).apply {
                        duration = 500
                    }
                }
            )
        }
    }
}