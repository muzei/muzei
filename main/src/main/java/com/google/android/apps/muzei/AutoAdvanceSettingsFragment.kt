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

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.style.UnderlineSpan
import android.util.SparseIntArray
import android.util.SparseLongArray
import android.view.View
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.text.set
import androidx.core.text.toSpannable
import androidx.fragment.app.Fragment
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

class AutoAdvanceSettingsFragment : Fragment(R.layout.auto_advance_settings_fragment) {
    companion object {
        private const val TASKER_PACKAGE_NAME = "net.dinglisch.android.taskerm"

        private val INTERVAL_RADIO_BUTTON_IDS_BY_TIME = SparseIntArray()
        private val INTERVAL_TIME_BY_RADIO_BUTTON_ID = SparseLongArray()

        init {
            INTERVAL_RADIO_BUTTON_IDS_BY_TIME.apply {
                put(60 * 15, R.id.auto_advance_interval_15m)
                put(60 * 30, R.id.auto_advance_interval_30m)
                put(60 * 60, R.id.auto_advance_interval_1h)
                put(60 * 60 * 3, R.id.auto_advance_interval_3h)
                put(60 * 60 * 6, R.id.auto_advance_interval_6h)
                put(60 * 60 * 24, R.id.auto_advance_interval_24h)
                put(60 * 60 * 72, R.id.auto_advance_interval_72h)
                put(0, R.id.auto_advance_interval_never)
            }
            for (i in 0 until INTERVAL_RADIO_BUTTON_IDS_BY_TIME.size()) {
                INTERVAL_TIME_BY_RADIO_BUTTON_ID.put(INTERVAL_RADIO_BUTTON_IDS_BY_TIME.valueAt(i),
                        INTERVAL_RADIO_BUTTON_IDS_BY_TIME.keyAt(i).toLong())
            }
        }
    }

    @SuppressLint("InlinedApi")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val providerManager = ProviderManager.getInstance(requireContext())
        val autoAdvanceWifi: CheckBox = view.findViewById(R.id.auto_advance_wifi)
        autoAdvanceWifi.isChecked = providerManager.loadOnWifi
        autoAdvanceWifi.setOnCheckedChangeListener { _, isChecked ->
            FirebaseAnalytics.getInstance(requireContext()).logEvent(
                    "auto_advance_load_on_wifi", bundleOf(
                    FirebaseAnalytics.Param.VALUE to isChecked.toString()))
            providerManager.loadOnWifi = isChecked
        }

        val currentInterval = providerManager.loadFrequencySeconds
        val intervalRadioGroup: RadioGroup = view.findViewById(R.id.auto_advance_interval)

        intervalRadioGroup.check(INTERVAL_RADIO_BUTTON_IDS_BY_TIME[currentInterval.toInt()])
        intervalRadioGroup.setOnCheckedChangeListener { _, id ->
            FirebaseAnalytics.getInstance(requireContext()).logEvent(
                    "auto_advance_load_frequency", bundleOf(
                    FirebaseAnalytics.Param.VALUE to INTERVAL_TIME_BY_RADIO_BUTTON_ID[id]))
            providerManager.loadFrequencySeconds = INTERVAL_TIME_BY_RADIO_BUTTON_ID[id]
        }

        val tasker: TextView = view.findViewById(R.id.auto_advance_tasker)
        val text = tasker.text.toSpannable()
        val taskerIndex = text.indexOf("Tasker")
        text[taskerIndex, taskerIndex + 6] = UnderlineSpan()
        tasker.text = text
        val context = requireContext()
        tasker.setOnClickListener {
            try {
                val pm = context.packageManager
                context.startActivity(
                        (pm.getLaunchIntentForPackage(TASKER_PACKAGE_NAME)
                                ?: Intent(Intent.ACTION_VIEW, Uri.parse(
                                        "https://play.google.com/store/apps/details?id=" +
                                                TASKER_PACKAGE_NAME +
                                                "&referrer=utm_source%3Dmuzei" +
                                                "%26utm_medium%3Dapp" +
                                                "%26utm_campaign%3Dauto_advance"))
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT))
                FirebaseAnalytics.getInstance(context).logEvent("tasker_open", null)
            } catch (e: ActivityNotFoundException) {
                context.toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
            } catch (e: SecurityException) {
                context.toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
            }
        }
    }
}
