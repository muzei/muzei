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

package com.google.android.apps.muzei.notifications;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v7.app.AlertDialog;

import net.nurik.roman.muzei.R;

public class NotificationSettingsDialogFragment extends DialogFragment {
    /**
     * Show the notification settings. This may come in the form of this dialog or the
     * system notification settings on O+ devices.
     */
    public static void showSettings(Fragment fragment) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Context context = fragment.getContext();
            // Ensure the notification channel exists
            NewWallpaperNotificationReceiver.createNotificationChannel(context);
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            // Open the specific channel since we only have one notification channel
            intent.putExtra(Settings.EXTRA_CHANNEL_ID,
                    NewWallpaperNotificationReceiver.NOTIFICATION_CHANNEL);
            context.startActivity(intent);
        } else {
            new NotificationSettingsDialogFragment().show(
                    fragment.getChildFragmentManager(), "notifications");
        }
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Context context = getContext();
        if (context == null) {
            return super.onCreateDialog(savedInstanceState);
        }
        CharSequence[] items = new CharSequence[]
                { context.getText(R.string.notification_new_wallpaper_channel_name)};
        final SharedPreferences sharedPreferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        boolean[] checkedItems = new boolean[]
                { sharedPreferences
                        .getBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, true)};
        return new AlertDialog.Builder(getContext())
                .setTitle(R.string.notification_settings)
                .setMultiChoiceItems(items, checkedItems, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        sharedPreferences.edit().putBoolean(
                                NewWallpaperNotificationReceiver.PREF_ENABLED, isChecked).apply();
                    }
                })
                .setPositiveButton(R.string.notification_settings_done, null)
                .create();
    }
}