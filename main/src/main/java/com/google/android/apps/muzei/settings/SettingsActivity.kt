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

package com.google.android.apps.muzei.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.observe
import com.google.android.apps.muzei.MissingResourcesDialogFragment
import com.google.android.apps.muzei.render.MuzeiRendererFragment
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import net.nurik.roman.muzei.R

/**
 * The primary wallpaper configuration activity. Serves as an interstitial when adding
 * the wallpaper, and shows when pressing the settings button in the wallpaper selector.
 */
class SettingsActivity : AppCompatActivity() {

    private var renderLocally: Boolean = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MissingResourcesDialogFragment.showDialogIfNeeded(this)) {
            return
        }
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        setContentView(R.layout.settings_activity)

        WallpaperActiveState.observe(this) { isActive ->
            updateRenderLocally(!isActive)
        }
    }

    private fun updateRenderLocally(renderLocally: Boolean) {
        if (this.renderLocally == renderLocally) {
            return
        }

        this.renderLocally = renderLocally

        val uiContainer = findViewById<View>(R.id.effects_fragment)

        val fm = supportFragmentManager
        val localRenderFragment = fm.findFragmentById(R.id.local_render_container)
        if (renderLocally) {
            if (localRenderFragment == null) {
                fm.commit {
                    add(R.id.local_render_container,
                            MuzeiRendererFragment.createInstance(false))
                }
            }
            uiContainer.setBackgroundColor(0x00000000) // for ripple touch feedback
        } else {
            if (localRenderFragment != null) {
                fm.commit {
                    remove(localRenderFragment)
                }
            }
            uiContainer.background = null
        }
    }
}
