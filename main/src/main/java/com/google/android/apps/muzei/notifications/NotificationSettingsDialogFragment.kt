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
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Settings
import android.support.v4.app.DialogFragment
import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog
import androidx.core.content.edit
import net.nurik.roman.muzei.R

class NotificationSettingsDialogFragment : DialogFragment() {

    companion object {
        /**
         * Show the notification settings. This may come in the form of this dialog or the
         * system notification settings on O+ devices.
         */
        fun showSettings(fragment: Fragment) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val context = fragment.requireContext()
                // Ensure the notification channel exists
                NewWallpaperNotificationReceiver.createNotificationChannel(context)
                val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                // Open the specific channel since we only have one notification channel
                intent.putExtra(Settings.EXTRA_CHANNEL_ID,
                        NewWallpaperNotificationReceiver.NOTIFICATION_CHANNEL)
                context.startActivity(intent)
            } else {
                NotificationSettingsDialogFragment().show(
                        fragment.childFragmentManager, "notifications")
            }
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val context = context ?: return super.onCreateDialog(savedInstanceState)
        val items = arrayOf(context.getText(R.string.notification_new_wallpaper_channel_name))
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val checkedItems = booleanArrayOf(sharedPreferences
                .getBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, true))
        return AlertDialog.Builder(context)
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