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

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.View
import android.view.ViewPropertyAnimator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.text.HtmlCompat
import androidx.fragment.app.commit
import com.google.android.apps.muzei.render.MuzeiRendererFragment
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

class AboutActivity : AppCompatActivity() {

    private var animator: ViewPropertyAnimator? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.about_activity)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        (findViewById<View>(R.id.app_bar) as Toolbar).setNavigationOnClickListener { onNavigateUp() }

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                add(R.id.demo_view_container,
                        MuzeiRendererFragment.createInstance(true))
            }
        }

        // Build the about body view and append the link to see OSS licenses
        findViewById<TextView>(R.id.app_version).apply {
            text = getString(R.string.about_version_template, BuildConfig.VERSION_NAME)
        }

        findViewById<TextView>(R.id.about_body).apply {
            text = HtmlCompat.fromHtml(getString(R.string.about_body), 0)
            movementMethod = LinkMovementMethod()
        }

        findViewById<View>(R.id.android_experiment_link).setOnClickListener {
            val cti = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .setToolbarColor(ContextCompat.getColor(this, R.color.theme_primary))
                    .build()
            try {
                cti.launchUrl(this,
                        "https://www.androidexperiments.com/experiment/muzei".toUri())
            } catch (ignored: ActivityNotFoundException) {
            }
        }
    }

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)

        val demoContainerView = findViewById<View>(R.id.demo_view_container).apply {
            alpha = 0f
        }
        animator = demoContainerView.animate()
                .alpha(1f)
                .setStartDelay(250)
                .setDuration(1000)
                .withEndAction {
                    val logoFragment = supportFragmentManager.findFragmentById(R.id.animated_logo_fragment)
                            as? AnimatedMuzeiLogoFragment
                    logoFragment?.start()
                }
    }

    override fun onDestroy() {
        animator?.cancel()
        super.onDestroy()
    }
}
