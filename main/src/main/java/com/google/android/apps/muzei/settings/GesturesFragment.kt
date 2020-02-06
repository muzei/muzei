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

import android.os.Bundle
import android.view.View
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.GesturesFragmentBinding

class GesturesFragment: Fragment(R.layout.gestures_fragment) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = GesturesFragmentBinding.bind(view)
        binding.toolbar.apply {
            navigationIcon = DrawerArrowDrawable(requireContext()).apply {
                progress = 1f
            }
            setNavigationOnClickListener {
                findNavController().popBackStack()
            }
        }

        val prefs = Prefs.getSharedPreferences(requireContext())
        val doubleTapValue = prefs.getString(Prefs.PREF_DOUBLE_TAP,
                Prefs.PREF_TAP_ACTION_TEMP)
        binding.doubleTapAction.check(when (doubleTapValue) {
            Prefs.PREF_TAP_ACTION_TEMP -> R.id.double_tap_temporary_disable
            Prefs.PREF_TAP_ACTION_NEXT -> R.id.double_tap_next
            Prefs.PREF_TAP_ACTION_VIEW_DETAILS -> R.id.double_tap_view_details
            else -> R.id.double_tap_none
        })
        binding.doubleTapAction.setOnCheckedChangeListener { _, index ->
            val newValue = when(index) {
                R.id.double_tap_temporary_disable -> Prefs.PREF_TAP_ACTION_TEMP
                R.id.double_tap_next -> Prefs.PREF_TAP_ACTION_NEXT
                R.id.double_tap_view_details -> Prefs.PREF_TAP_ACTION_VIEW_DETAILS
                else -> Prefs.PREF_TAP_ACTION_NONE
            }
            prefs.edit {
                putString(Prefs.PREF_DOUBLE_TAP, newValue)
            }
        }

        val threeFingerTapValue = prefs.getString(Prefs.PREF_THREE_FINGER_TAP,
                Prefs.PREF_TAP_ACTION_NONE)
        binding.threeFingerTapAction.check(when (threeFingerTapValue) {
            Prefs.PREF_TAP_ACTION_TEMP -> R.id.three_finger_tap_temporary_disable
            Prefs.PREF_TAP_ACTION_NEXT -> R.id.three_finger_tap_next
            Prefs.PREF_TAP_ACTION_VIEW_DETAILS -> R.id.three_finger_tap_view_details
            else -> R.id.three_finger_tap_none
        })
        binding.threeFingerTapAction.setOnCheckedChangeListener { _, index ->
            val newValue = when(index) {
                R.id.three_finger_tap_temporary_disable -> Prefs.PREF_TAP_ACTION_TEMP
                R.id.three_finger_tap_next -> Prefs.PREF_TAP_ACTION_NEXT
                R.id.three_finger_tap_view_details -> Prefs.PREF_TAP_ACTION_VIEW_DETAILS
                else -> Prefs.PREF_TAP_ACTION_NONE
            }
            prefs.edit {
                putString(Prefs.PREF_THREE_FINGER_TAP, newValue)
            }
        }
    }
}
