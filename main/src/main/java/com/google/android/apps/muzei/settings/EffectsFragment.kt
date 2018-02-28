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
import android.os.Handler
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.SeekBar
import androidx.content.edit

import com.google.android.apps.muzei.render.MuzeiBlurRenderer

import net.nurik.roman.muzei.R

/**
 * Fragment for allowing the user to configure advanced settings.
 */
class EffectsFragment : Fragment() {

    private val handler = Handler()
    private lateinit var blurSeekBar: SeekBar
    private lateinit var dimSeekBar: SeekBar
    private lateinit var greySeekBar: SeekBar

    private val mUpdateBlurRunnable = Runnable {
        Prefs.getSharedPreferences(requireContext()).edit {
            putInt(Prefs.PREF_BLUR_AMOUNT, blurSeekBar.progress)
        }
    }

    private val mUpdateDimRunnable = Runnable {
        Prefs.getSharedPreferences(requireContext()).edit {
            putInt(Prefs.PREF_DIM_AMOUNT, dimSeekBar.progress)
        }
    }

    private val mUpdateGreyRunnable = Runnable {
        Prefs.getSharedPreferences(requireContext()).edit {
            putInt(Prefs.PREF_GREY_AMOUNT, greySeekBar.progress)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.settings_advanced_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        @Suppress("DEPRECATION")
        view.requestFitSystemWindows()

        blurSeekBar = view.findViewById(R.id.blur_amount)
        blurSeekBar.progress = Prefs.getSharedPreferences(requireContext())
                .getInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR)
        blurSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    handler.removeCallbacks(mUpdateBlurRunnable)
                    handler.postDelayed(mUpdateBlurRunnable, 750)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        dimSeekBar = view.findViewById(R.id.dim_amount)
        dimSeekBar.progress = Prefs.getSharedPreferences(requireContext())
                .getInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
        dimSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    handler.removeCallbacks(mUpdateDimRunnable)
                    handler.postDelayed(mUpdateDimRunnable, 750)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        greySeekBar = view.findViewById(R.id.grey_amount)
        greySeekBar.progress = Prefs.getSharedPreferences(requireContext())
                .getInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY)
        greySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    handler.removeCallbacks(mUpdateGreyRunnable)
                    handler.postDelayed(mUpdateGreyRunnable, 750)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        val mBlurOnLockScreenCheckBox = view.findViewById<CheckBox>(
                R.id.blur_on_lockscreen_checkbox)
        mBlurOnLockScreenCheckBox.setOnCheckedChangeListener { _, checked ->
            Prefs.getSharedPreferences(requireContext()).edit()
                    .putBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, !checked)
                    .apply()
        }
        mBlurOnLockScreenCheckBox.isChecked = !Prefs.getSharedPreferences(requireContext())
                .getBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, false)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_advanced, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_reset_defaults -> {
                Prefs.getSharedPreferences(requireContext()).edit {
                    putInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR)
                    putInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
                    putInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY)
                }
                blurSeekBar.progress = MuzeiBlurRenderer.DEFAULT_BLUR
                dimSeekBar.progress = MuzeiBlurRenderer.DEFAULT_MAX_DIM
                greySeekBar.progress = MuzeiBlurRenderer.DEFAULT_GREY
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
