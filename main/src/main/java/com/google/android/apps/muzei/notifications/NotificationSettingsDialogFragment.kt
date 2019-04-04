/*
 * Copyright 2017 Google Inc.
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

package com.google.android.apps.muzei.notifications

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.google.android.apps.muzei.util.toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import net.nurik.roman.muzei.R

class NotificationSettingsDialogFragment : DialogFragment() {

    companion object {
        /**
         * Show the notification settings. This may come in the form of this dialog or the
         * system notification settings on O+ devices.
         */
        fun showSettings(context: Context, fragmentManager: FragmentManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Ensure the notification channel exists
                NewWallpaperNotificationReceiver.createNotificationChannel(context)
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                // Open the specific channel since we only have one notification channel
                intent.putExtra(Settings.EXTRA_CHANNEL_ID,
                        NewWallpaperNotificationReceiver.NOTIFICATION_CHANNEL)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                } else {
                    context.toast(R.string.notification_settings_failed, Toast.LENGTH_LONG)
                }
            } else {
                NotificationSettingsDialogFragment().show(
                        fragmentManager, "notifications")
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = context ?: return super.onCreateDialog(savedInstanceState)
        val items = arrayOf(context.getText(R.string.notification_new_wallpaper_channel_name))
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val checkedItems = booleanArrayOf(sharedPreferences
                .getBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, true))
        return MaterialAlertDialogBuilder(context)
                .setTitle(R.string.notification_settings)
                .setMultiChoiceItems(items, checkedItems) { _, _, isChecked ->
                    sharedPreferences.edit {
                        putBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, isChecked)
                    }
                }
                .setPositiveButton(R.string.notification_settings_done, null)
                .create()
    }
}