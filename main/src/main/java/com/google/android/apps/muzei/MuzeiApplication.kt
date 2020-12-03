/*
 * Copyright 2019 Google Inc.
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

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import androidx.multidex.MultiDexApplication
import com.google.android.apps.muzei.settings.Prefs

class MuzeiApplication : MultiDexApplication(), SharedPreferences.OnSharedPreferenceChangeListener {

    companion object {
        private const val ALWAYS_DARK_KEY = "always_dark"

        fun setAlwaysDark(context: Context, alwaysDark: Boolean) {
            Prefs.getSharedPreferences(context).edit {
                putBoolean(ALWAYS_DARK_KEY, alwaysDark)
            }
        }

        fun getAlwaysDark(context: Context) =
                Prefs.getSharedPreferences(context).getBoolean(ALWAYS_DARK_KEY, false)
    }

    override fun onCreate() {
        super.onCreate()
        updateNightMode()
        val sharedPreferences = Prefs.getSharedPreferences(this)
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (key == ALWAYS_DARK_KEY) {
            updateNightMode()
        }
    }

    private fun updateNightMode() {
        val alwaysDark = getAlwaysDark(this)
        AppCompatDelegate.setDefaultNightMode(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !alwaysDark)
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else
                    AppCompatDelegate.MODE_NIGHT_YES
        )
    }
}
