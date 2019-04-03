/*
 * Copyright 2018 Google Inc.
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

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.util.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

/**
 * Fragment for allowing the user to configure advanced settings.
 */
class EffectsScreenFragment : Fragment(R.layout.effects_screen_fragment) {

    companion object {
        private const val PREF_BLUR = "pref_blur"
        private const val PREF_DIM = "pref_dim"
        private const val PREF_GREY = "pref_grey"

        internal fun create(
                prefBlur: String,
                prefDim: String,
                prefGrey: String
        ) = EffectsScreenFragment().apply {
            arguments = bundleOf(
                    PREF_BLUR to prefBlur,
                    PREF_DIM to prefDim,
                    PREF_GREY to prefGrey
            )
        }
    }

    private lateinit var blurPref: String
    private lateinit var dimPref: String
    private lateinit var greyPref: String

    private lateinit var blurSeekBar: SeekBar
    private lateinit var dimSeekBar: SeekBar
    private lateinit var greySeekBar: SeekBar

    private lateinit var blurOnPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var dimOnPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener
    private lateinit var greyOnPreferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    private var updateBlur: Job? = null
    private var updateDim: Job? = null
    private var updateGrey: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blurPref = requireArguments().getString(PREF_BLUR)
                ?: throw IllegalArgumentException("Missing required argument $PREF_BLUR")
        dimPref = requireArguments().getString(PREF_DIM)
                ?: throw IllegalArgumentException("Missing required argument $PREF_DIM")
        greyPref = requireArguments().getString(PREF_GREY)
                ?: throw IllegalArgumentException("Missing required argument $PREF_GREY")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = Prefs.getSharedPreferences(requireContext())
        blurSeekBar = view.findViewById(R.id.blur_amount)
        blurSeekBar.progress = prefs.getInt(blurPref, MuzeiBlurRenderer.DEFAULT_BLUR)
        blurSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateBlur?.cancel()
                    updateBlur = coroutineScope.launch {
                        delay(750)
                        prefs.edit {
                            putInt(blurPref, blurSeekBar.progress)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        blurOnPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, _ ->
            blurSeekBar.progress = prefs.getInt(blurPref, MuzeiBlurRenderer.DEFAULT_BLUR)
        }
        prefs.registerOnSharedPreferenceChangeListener(blurOnPreferenceChangeListener)

        dimSeekBar = view.findViewById(R.id.dim_amount)
        dimSeekBar.progress = prefs.getInt(dimPref, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
        dimSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateDim?.cancel()
                    updateDim = coroutineScope.launch {
                        delay(750)
                        prefs.edit {
                            putInt(dimPref, dimSeekBar.progress)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        dimOnPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, _ ->
            dimSeekBar.progress = prefs.getInt(dimPref, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
        }
        prefs.registerOnSharedPreferenceChangeListener(dimOnPreferenceChangeListener)

        greySeekBar = view.findViewById(R.id.grey_amount)
        greySeekBar.progress = prefs.getInt(greyPref, MuzeiBlurRenderer.DEFAULT_GREY)
        greySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    updateGrey?.cancel()
                    updateGrey = coroutineScope.launch {
                        delay(750)
                        prefs.edit {
                            putInt(greyPref, greySeekBar.progress)
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}

            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        greyOnPreferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener {
            _, _ ->
            greySeekBar.progress = prefs.getInt(greyPref, MuzeiBlurRenderer.DEFAULT_GREY)
        }
        prefs.registerOnSharedPreferenceChangeListener(greyOnPreferenceChangeListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = Prefs.getSharedPreferences(requireContext())
        prefs.unregisterOnSharedPreferenceChangeListener(
                blurOnPreferenceChangeListener)
        prefs.unregisterOnSharedPreferenceChangeListener(
                dimOnPreferenceChangeListener)
        prefs.unregisterOnSharedPreferenceChangeListener(
                greyOnPreferenceChangeListener)
    }
}
