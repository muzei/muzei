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
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast

import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment
import com.google.firebase.analytics.FirebaseAnalytics

import net.nurik.roman.muzei.R

class IntroFragment : Fragment() {

    private lateinit var mActivateButton: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            FirebaseAnalytics.getInstance(context!!).logEvent(FirebaseAnalytics.Event.TUTORIAL_BEGIN, null)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.intro_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        mActivateButton = view.findViewById(R.id.activate_muzei_button)
        mActivateButton.setOnClickListener {
            FirebaseAnalytics.getInstance(context!!).logEvent("activate", null)
            try {
                startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                        .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(context,
                                        MuzeiWallpaperService::class.java))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                try {
                    startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e2: ActivityNotFoundException) {
                    Toast.makeText(context, R.string.error_wallpaper_chooser,
                            Toast.LENGTH_LONG).show()
                }

            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (savedInstanceState == null) {
            val logoFragment = AnimatedMuzeiLogoFragment()
            childFragmentManager.beginTransaction()
                    .add(R.id.animated_logo_fragment, logoFragment)
                    .commitNow()

            mActivateButton.alpha = 0f
            logoFragment.setOnFillStartedCallback(Runnable {
                mActivateButton.animate().alpha(1f).setDuration(500)
            })
            mActivateButton.postDelayed({
                if (logoFragment.isAdded) {
                    logoFragment.start()
                }
            }, 1000)
        }
    }
}
