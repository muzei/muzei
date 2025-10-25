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
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.compose.content
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.theme.AppTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Fragment for allowing the user to configure advanced settings.
 */
class EffectsScreenFragment : Fragment() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blurPref = requireArguments().getString(PREF_BLUR)
            ?: throw IllegalArgumentException("Missing required argument $PREF_BLUR")
        dimPref = requireArguments().getString(PREF_DIM)
            ?: throw IllegalArgumentException("Missing required argument $PREF_DIM")
        greyPref = requireArguments().getString(PREF_GREY)
            ?: throw IllegalArgumentException("Missing required argument $PREF_GREY")
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ) = content {
        AppTheme(
            dynamicColor = false,
        ) {
            val context = LocalContext.current
            val prefs = remember { Prefs.getSharedPreferences(context) }
            val blur =
                rememberPreferenceSourcedValue(prefs, blurPref, MuzeiBlurRenderer.DEFAULT_BLUR)
            val dim =
                rememberPreferenceSourcedValue(prefs, dimPref, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
            val grey =
                rememberPreferenceSourcedValue(prefs, greyPref, MuzeiBlurRenderer.DEFAULT_GREY)
            EffectsScreen(
                blur = blur.value,
                onBlurChange = {
                    blur.value = it
                },
                onBlurChangeFinished = {
                    blur.userControlled = false
                },
                dim = dim.value,
                onDimChange = {
                    dim.value = it
                },
                onDimChangeFinished = {
                    dim.userControlled = false
                },
                grey = grey.value,
                onGreyChange = {
                    grey.value = it
                },
                onGreyChangeFinished = {
                    grey.userControlled = false
                }
            )
        }
    }
}

private class PreferenceSourcedValue(
    private val prefs: SharedPreferences,
    private val prefName: String,
    private val coroutineScope: CoroutineScope,
    private val userControlledValue: MutableIntState,
    private val preferenceControlledValue: State<Int>
) {
    var userControlled by mutableStateOf(false)
    private var updateJob by mutableStateOf<Job?>(null)

    var value: Int
        get() = if (userControlled || updateJob != null) userControlledValue.intValue else preferenceControlledValue.value
        set(value) {
            userControlledValue.intValue = value
            userControlled = true
            updateJob?.cancel()
            updateJob = coroutineScope.launch {
                delay(750)
                prefs.edit {
                    putInt(prefName, value)
                }
                updateJob = null
            }
        }
}

@Composable
private fun rememberPreferenceSourcedValue(
    prefs: SharedPreferences,
    prefName: String,
    defaultValue: Int,
): PreferenceSourcedValue {
    val defaultValue = remember {
        prefs.getInt(prefName, defaultValue)
    }
    val userControlledValue = remember { mutableIntStateOf(defaultValue) }
    val preferenceControlledValue = produceState(defaultValue) {
        callbackFlow {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == prefName) {
                    trySend(prefs.getInt(prefName, defaultValue))
                }

            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose {
                prefs.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }.collect {
            value = it
        }
    }
    val coroutineScope = rememberCoroutineScope()
    return remember(
        prefs, prefName, coroutineScope, userControlledValue, preferenceControlledValue
    ) {
        PreferenceSourcedValue(
            prefs, prefName, coroutineScope, userControlledValue, preferenceControlledValue
        )
    }
}