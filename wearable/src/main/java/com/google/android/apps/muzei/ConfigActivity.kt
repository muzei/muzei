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

package com.google.android.apps.muzei

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.preference.Preference
import android.preference.PreferenceActivity
import android.preference.PreferenceManager
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.ComplicationHelperActivity
import android.support.wearable.complications.ComplicationProviderInfo
import android.support.wearable.complications.ProviderChooserIntent
import android.support.wearable.complications.ProviderInfoRetriever
import androidx.core.content.ContextCompat
import net.nurik.roman.muzei.R
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Configuration Activity for [MuzeiWatchFace]
 */
class ConfigActivity : PreferenceActivity() {

    companion object {
        private const val CHOOSE_TOP_COMPLICATION_REQUEST_CODE = 1
        private const val CHOOSE_BOTTOM_COMPLICATION_REQUEST_CODE = 2
        internal const val SHOW_DATE_PREFERENCE_KEY = "config_show_date"
        internal const val TAP_PREFERENCE_KEY = "config_tap"
    }

    private val executor : Executor by lazy {
        Executors.newSingleThreadExecutor()
    }
    private val providerInfoRetriever : ProviderInfoRetriever by lazy {
        ProviderInfoRetriever(this, executor)
    }
    private lateinit var topPreference: Preference
    private lateinit var bottomPreference: Preference

    @Suppress("DEPRECATION")
    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        addPreferencesFromResource(R.xml.config_preferences)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            providerInfoRetriever.init()
            topPreference = findPreference("config_top_complication")
            bottomPreference = findPreference("config_bottom_complication")
            providerInfoRetriever.retrieveProviderInfo(
                    object : ProviderInfoRetriever.OnProviderInfoReceivedCallback() {
                        override fun onProviderInfoReceived(
                                watchFaceComplicationId: Int,
                                info: ComplicationProviderInfo?
                        ) {
                            when (watchFaceComplicationId) {
                                MuzeiWatchFace.TOP_COMPLICATION_ID ->
                                    updateComplicationPreference(topPreference, info)
                                MuzeiWatchFace.BOTTOM_COMPLICATION_ID ->
                                    updateComplicationPreference(bottomPreference, info)
                            }
                        }
                    },
                    ComponentName(this, MuzeiWatchFace::class.java),
                    MuzeiWatchFace.TOP_COMPLICATION_ID, MuzeiWatchFace.BOTTOM_COMPLICATION_ID)
            topPreference.apply {
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    selectTopComplication()
                    true
                }
            }
            bottomPreference.apply {
                onPreferenceClickListener = Preference.OnPreferenceClickListener {
                    selectBottomComplication()
                    true
                }
            }
        }
        findPreference(TAP_PREFERENCE_KEY)?.let { preference ->
            updateTapPreference(preference)
            preference.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _,
                    newValue ->
                updateTapPreference(preference, newValue as String?)
                true
            }
        }
    }

    private fun updateComplicationPreference(
            preference: Preference,
            info: ComplicationProviderInfo?
    ) {
        preference.icon = info?.providerIcon?.loadDrawable(this@ConfigActivity)
                ?: ContextCompat.getDrawable(this@ConfigActivity,
                R.drawable.ic_config_empty_complication)
        preference.summary = info?.providerName
    }

    private fun updateTapPreference(
            preference: Preference,
            newValue: String? = null
    ) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val tapAction = newValue ?: sharedPreferences.getString(TAP_PREFERENCE_KEY,
                getString(R.string.config_tap_default))
        val values = resources.getStringArray(R.array.config_tap_values)
        val entries = resources.getStringArray(R.array.config_tap_entries)
        preference.summary = entries[values.indexOf(tapAction)]
    }

    private fun selectTopComplication() {
        val intent = ComplicationHelperActivity.createProviderChooserHelperIntent(this,
                ComponentName(this, MuzeiWatchFace::class.java),
                MuzeiWatchFace.TOP_COMPLICATION_ID,
                ComplicationData.TYPE_RANGED_VALUE,
                ComplicationData.TYPE_SHORT_TEXT,
                ComplicationData.TYPE_SMALL_IMAGE,
                ComplicationData.TYPE_ICON)
        startActivityForResult(intent, CHOOSE_TOP_COMPLICATION_REQUEST_CODE)
    }

    private fun selectBottomComplication() {
        val intent = ComplicationHelperActivity.createProviderChooserHelperIntent(this,
                ComponentName(this, MuzeiWatchFace::class.java),
                MuzeiWatchFace.BOTTOM_COMPLICATION_ID,
                ComplicationData.TYPE_LONG_TEXT,
                ComplicationData.TYPE_RANGED_VALUE,
                ComplicationData.TYPE_SHORT_TEXT)
        startActivityForResult(intent, CHOOSE_BOTTOM_COMPLICATION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            CHOOSE_TOP_COMPLICATION_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    updateComplicationPreference(
                            topPreference,
                            data?.getParcelableExtra(ProviderChooserIntent.EXTRA_PROVIDER_INFO)
                                    as ComplicationProviderInfo?)

                }
            }
            CHOOSE_BOTTOM_COMPLICATION_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    updateComplicationPreference(
                            bottomPreference,
                            data?.getParcelableExtra(ProviderChooserIntent.EXTRA_PROVIDER_INFO)
                                    as ComplicationProviderInfo?)

                }
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun isValidFragment(fragmentName: String?): Boolean {
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            providerInfoRetriever.release()
        }
    }
}
