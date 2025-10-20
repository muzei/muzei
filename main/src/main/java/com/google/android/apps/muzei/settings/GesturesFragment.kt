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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.navigation.fragment.findNavController
import com.google.android.apps.muzei.theme.AppTheme
import net.nurik.roman.muzei.R

class GesturesFragment : Fragment() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = content {
        AppTheme(
            dynamicColor = false
        ) {
            val context = LocalContext.current
            val prefs = remember { Prefs.getSharedPreferences(context) }
            val gestureOptions = listOf(
                stringResource(R.string.gestures_tap_action_temporary_disable),
                stringResource(R.string.gestures_tap_action_next),
                stringResource(R.string.gestures_tap_action_view_details),
                stringResource(R.string.gestures_tap_action_none),
            )
            val prefToStringMapper: (prefValue: String?) -> String = { prefValue ->
                when (prefValue) {
                    Prefs.PREF_TAP_ACTION_TEMP -> gestureOptions[0]
                    Prefs.PREF_TAP_ACTION_NEXT -> gestureOptions[1]
                    Prefs.PREF_TAP_ACTION_VIEW_DETAILS -> gestureOptions[2]
                    else -> gestureOptions[3]
                }
            }
            val stringToPrefMapper: (string: String) -> String = { string ->
                when (string) {
                    gestureOptions[0] -> Prefs.PREF_TAP_ACTION_TEMP
                    gestureOptions[1] -> Prefs.PREF_TAP_ACTION_NEXT
                    gestureOptions[2] -> Prefs.PREF_TAP_ACTION_VIEW_DETAILS
                    else -> Prefs.PREF_TAP_ACTION_NONE
                }
            }
            val defaultDoubleTapOption = remember {
                val doubleTapValue = prefs.getString(
                    Prefs.PREF_DOUBLE_TAP, Prefs.PREF_TAP_ACTION_TEMP
                )
                prefToStringMapper(doubleTapValue)
            }
            var doubleTapSelectedOption by remember { mutableStateOf(defaultDoubleTapOption) }
            val defaultThreeFingerOption = remember {
                val threeFingerValue = prefs.getString(
                    Prefs.PREF_THREE_FINGER_TAP, Prefs.PREF_TAP_ACTION_NONE
                )
                prefToStringMapper(threeFingerValue)
            }
            var threeFingerSelectedOption by remember { mutableStateOf(defaultThreeFingerOption) }
            GestureSettings(
                doubleTapSelectedOption = doubleTapSelectedOption,
                onDoubleTapSelectedOptionChange = { selectedOption ->
                    prefs.edit {
                        putString(Prefs.PREF_DOUBLE_TAP, stringToPrefMapper(selectedOption))
                    }
                    doubleTapSelectedOption = selectedOption
                },
                threeFingerSelectedOption = threeFingerSelectedOption,
                onThreeFingerSelectedOptionChange = { selectedOption ->
                    prefs.edit {
                        putString(Prefs.PREF_THREE_FINGER_TAP, stringToPrefMapper(selectedOption))
                    }
                    threeFingerSelectedOption = selectedOption
                },
                onUp = {
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.gestures_fragment) {
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}