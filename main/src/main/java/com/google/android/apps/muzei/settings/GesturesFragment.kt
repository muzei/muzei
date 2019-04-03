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

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import androidx.appcompat.graphics.drawable.DrawerArrowDrawable
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import net.nurik.roman.muzei.R

class GesturesFragment: Fragment(R.layout.gestures_fragment) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        ViewCompat.requestApplyInsets(view)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            requireActivity().window.statusBarColor = ContextCompat.getColor(
                    requireContext(), R.color.theme_primary_dark)
        }

        view.findViewById<Toolbar>(R.id.gestures_toolbar).apply {
            navigationIcon = DrawerArrowDrawable(requireContext()).apply {
                progress = 1f
            }
            setNavigationOnClickListener {
                findNavController().popBackStack()
            }
        }

        val prefs = Prefs.getSharedPreferences(requireContext())
        val doubleTap = view.findViewById<RadioGroup>(R.id.gestures_double_tap_action)
        val doubleTapValue = prefs.getString(Prefs.PREF_DOUBLE_TAP,
                Prefs.PREF_TAP_ACTION_TEMP)
        doubleTap.check(when (doubleTapValue) {
            Prefs.PREF_TAP_ACTION_TEMP -> R.id.gestures_double_tap_temporary_disable
            Prefs.PREF_TAP_ACTION_NEXT -> R.id.gestures_double_tap_next
            Prefs.PREF_TAP_ACTION_VIEW_DETAILS -> R.id.gestures_double_tap_view_details
            else -> R.id.gestures_double_tap_none
        })
        doubleTap.setOnCheckedChangeListener { _, index ->
            val newValue = when(index) {
                R.id.gestures_double_tap_temporary_disable -> Prefs.PREF_TAP_ACTION_TEMP
                R.id.gestures_double_tap_next -> Prefs.PREF_TAP_ACTION_NEXT
                R.id.gestures_double_tap_view_details -> Prefs.PREF_TAP_ACTION_VIEW_DETAILS
                else -> Prefs.PREF_TAP_ACTION_NONE
            }
            prefs.edit {
                putString(Prefs.PREF_DOUBLE_TAP, newValue)
            }
        }

        val threeFingerTap = view.findViewById<RadioGroup>(R.id.gestures_three_finger_tap_action)
        val threeFingerTapValue = prefs.getString(Prefs.PREF_THREE_FINGER_TAP,
                Prefs.PREF_TAP_ACTION_NONE)
        threeFingerTap.check(when (threeFingerTapValue) {
            Prefs.PREF_TAP_ACTION_TEMP -> R.id.gestures_three_finger_tap_temporary_disable
            Prefs.PREF_TAP_ACTION_NEXT -> R.id.gestures_three_finger_tap_next
            Prefs.PREF_TAP_ACTION_VIEW_DETAILS -> R.id.gestures_three_finger_tap_view_details
            else -> R.id.gestures_three_finger_tap_none
        })
        threeFingerTap.setOnCheckedChangeListener { _, index ->
            val newValue = when(index) {
                R.id.gestures_three_finger_tap_temporary_disable -> Prefs.PREF_TAP_ACTION_TEMP
                R.id.gestures_three_finger_tap_next -> Prefs.PREF_TAP_ACTION_NEXT
                R.id.gestures_three_finger_tap_view_details -> Prefs.PREF_TAP_ACTION_VIEW_DETAILS
                else -> Prefs.PREF_TAP_ACTION_NONE
            }
            prefs.edit {
                putString(Prefs.PREF_THREE_FINGER_TAP, newValue)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            requireActivity().window.statusBarColor = Color.TRANSPARENT
        }
    }
}
