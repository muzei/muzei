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

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import androidx.lifecycle.compose.LifecycleStartEffect
import com.google.android.apps.muzei.isPreviewMode
import com.google.android.apps.muzei.theme.AppTheme
import kotlinx.coroutines.flow.MutableStateFlow
import net.nurik.roman.muzei.R

val EffectsLockScreenOpen = MutableStateFlow(false)

/**
 * Fragment for allowing the user to configure advanced settings.
 */
class EffectsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = content {
        AppTheme(
            dynamicColor = false,
        ) {
            val pagerState = rememberPagerState(0) { 2 }
            LifecycleStartEffect(pagerState.currentPage) {
                EffectsLockScreenOpen.value = pagerState.currentPage == 1
                onStopOrDispose {
                    EffectsLockScreenOpen.value = false
                }
            }
            val context = LocalContext.current
            val prefs = remember { Prefs.getSharedPreferences(context) }
            EffectsSettings(
                prefs = prefs,
                navigationIcon = {
                    if (requireActivity().isPreviewMode) {
                        IconButton(onClick = {
                            requireActivity().run {
                                setResult(Activity.RESULT_OK)
                                finish()
                            }
                        }) {
                            Icon(
                                Icons.Default.Done,
                                contentDescription = stringResource(R.string.done)
                            )
                        }
                    }
                },
                pagerState = pagerState,
            )
        }
    }
}