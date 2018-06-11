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

import android.animation.ObjectAnimator
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentTransaction
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.Spinner
import android.widget.TextView
import com.google.android.apps.muzei.MissingResourcesDialogFragment
import com.google.android.apps.muzei.render.MuzeiRendererFragment
import com.google.android.apps.muzei.sources.ChooseSourceFragment
import com.google.android.apps.muzei.util.observeNonNull
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

/**
 * The primary widget configuration activity. Serves as an interstitial when adding the widget, and
 * shows when pressing the settings button in the widget.
 */
class SettingsActivity : AppCompatActivity(), ChooseSourceFragment.Callbacks {

    companion object {
        private const val EXTRA_START_SECTION = "com.google.android.apps.muzei.settings.extra.START_SECTION"

        private const val START_SECTION_SOURCE = 0
        private const val START_SECTION_ADVANCED = 1

        private val SECTION_LABELS = intArrayOf(R.string.section_choose_source, R.string.section_advanced)

        private val SECTION_FRAGMENTS = arrayOf(
                ChooseSourceFragment::class.java,
                EffectsFragment::class.java)

        private val SECTION_SCREEN_NAME = arrayOf("ChooseSource", "Effects")
    }

    private val startSection = START_SECTION_SOURCE

    private var backgroundAnimator: ObjectAnimator? = null
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

        // Set up UI widgets
        setupAppBar()

        backgroundAnimator?.cancel()
        backgroundAnimator = ObjectAnimator.ofFloat(this, "backgroundOpacity",
                0f, 1f).apply {
            duration = 1000
            start()
        }

        WallpaperActiveState.observeNonNull(this) { isActive ->
            updateRenderLocally(!isActive)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        backgroundAnimator?.cancel()
    }

    private fun setupAppBar() {
        val toolbar = findViewById<Toolbar>(R.id.app_bar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        val inflater = LayoutInflater.from(this)
        val sectionSpinner = findViewById<Spinner>(R.id.section_spinner)
        sectionSpinner.adapter = object : BaseAdapter() {
            override fun getCount(): Int {
                return SECTION_LABELS.size
            }

            override fun getItem(position: Int): Any {
                return SECTION_LABELS[position]
            }

            override fun getItemId(position: Int): Long {
                return (position + 1).toLong()
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: inflater.inflate(R.layout.settings_ab_spinner_list_item,
                        parent, false)
                view.findViewById<TextView>(android.R.id.text1).text = getString(SECTION_LABELS[position])
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView
                        ?: inflater.inflate(R.layout.settings_ab_spinner_list_item_dropdown,
                                parent, false)
                view.findViewById<TextView>(android.R.id.text1).text = getString(SECTION_LABELS[position])
                return view
            }
        }

        sectionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(spinner: AdapterView<*>, view: View?, position: Int, id: Long) {
                val fragmentClass = SECTION_FRAGMENTS[position]
                val currentFragment = supportFragmentManager.findFragmentById(
                        R.id.content_container)
                if (currentFragment != null && fragmentClass == currentFragment.javaClass) {
                    return
                }

                try {
                    val newFragment: Fragment = fragmentClass.newInstance()
                    FirebaseAnalytics.getInstance(this@SettingsActivity)
                            .setCurrentScreen(this@SettingsActivity, SECTION_SCREEN_NAME[position],
                                    fragmentClass.simpleName)
                    supportFragmentManager.beginTransaction()
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .setTransitionStyle(R.style.Muzei_SimpleFadeFragmentAnimation)
                            .replace(R.id.content_container, newFragment)
                            .commitAllowingStateLoss()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }

            override fun onNothingSelected(spinner: AdapterView<*>) {}
        }

        sectionSpinner.setSelection(startSection)
    }

    private fun updateRenderLocally(renderLocally: Boolean) {
        if (this.renderLocally == renderLocally) {
            return
        }

        this.renderLocally = renderLocally

        val uiContainer = findViewById<View>(R.id.container)
        val localRenderContainer = findViewById<ViewGroup>(R.id.local_render_container)

        val fm = supportFragmentManager
        val localRenderFragment = fm.findFragmentById(R.id.local_render_container)
        if (renderLocally) {
            if (localRenderFragment == null) {
                fm.beginTransaction()
                        .add(R.id.local_render_container,
                                MuzeiRendererFragment.createInstance(false))
                        .commit()
            }
            if (localRenderContainer.alpha == 1f) {
                localRenderContainer.alpha = 0f
            }
            localRenderContainer.visibility = View.VISIBLE
            localRenderContainer.animate()
                    .alpha(1f)
                    .setDuration(2000)
                    .withEndAction(null)
            uiContainer.setBackgroundColor(0x00000000) // for ripple touch feedback
        } else {
            if (localRenderFragment != null) {
                fm.beginTransaction()
                        .remove(localRenderFragment)
                        .commit()
            }
            localRenderContainer.animate()
                    .alpha(0f)
                    .setDuration(1000)
                    .withEndAction { localRenderContainer.visibility = View.GONE }
            uiContainer.background = null
        }
    }

    override fun onRequestCloseActivity() {
        finish()
    }
}
