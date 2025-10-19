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

package com.google.android.apps.muzei

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.theme.AppTheme
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics

class TutorialFragment : Fragment() {

    companion object {
        const val PREF_SEEN_TUTORIAL = "seen_tutorial"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = content {
        AppTheme(
            dynamicColor = false
        ) {
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {
                // We don't actually care if the user disables notifications from Muzei;
                // they can always re-enable them from the Notification Settings option
                // on the Sources screen
            }
            Tutorial(
                onAnimationCompleted = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    }
                },
                onClick = {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.TUTORIAL_COMPLETE, null)
                    PreferenceManager.getDefaultSharedPreferences(requireContext()).edit {
                        putBoolean(PREF_SEEN_TUTORIAL, true)
                    }
                }
            )
        }
    }
}