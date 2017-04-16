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

package com.google.android.apps.muzei.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.UserManagerCompat;

/**
 * Preference constants/helpers.
 */
public class Prefs {
    public static final String PREF_GREY_AMOUNT = "grey_amount";
    public static final String PREF_DIM_AMOUNT = "dim_amount";
    public static final String PREF_BLUR_AMOUNT = "blur_amount";
    public static final String PREF_DISABLE_BLUR_WHEN_LOCKED = "disable_blur_when_screen_locked_enabled";

    private static final String WALLPAPER_PREFERENCES_NAME = "wallpaper_preferences";
    private static final String PREF_MIGRATED = "migrated_from_default";

    public synchronized static SharedPreferences getSharedPreferences(Context context) {
        Context deviceProtectedContext =
                ContextCompat.createDeviceProtectedStorageContext(context);
        if (UserManagerCompat.isUserUnlocked(context)) {
            // First migrate the wallpaper settings to their own file
            SharedPreferences defaultSharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(context);
            SharedPreferences wallpaperPreferences = context.getSharedPreferences(
                    WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE);
            migratePreferences(defaultSharedPreferences, wallpaperPreferences);

            // Now migrate the file to device protected storage if available
            if (deviceProtectedContext != null) {
                SharedPreferences deviceProtectedPreferences = deviceProtectedContext.getSharedPreferences(
                        WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE);
                migratePreferences(wallpaperPreferences, deviceProtectedPreferences);
            }
        }
        // Now open the correct SharedPreferences
        Context contextToUse = deviceProtectedContext != null ? deviceProtectedContext : context;
        return contextToUse.getSharedPreferences(WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static void migratePreferences(SharedPreferences source, SharedPreferences destination) {
        if (source.getBoolean(PREF_MIGRATED, false)) {
            return;
        }
        SharedPreferences.Editor sourceEditor = source.edit();
        SharedPreferences.Editor destinationEditor = destination.edit();

        if (source.contains(PREF_GREY_AMOUNT)) {
            destinationEditor.putInt(PREF_GREY_AMOUNT, source.getInt(PREF_GREY_AMOUNT, 0));
            sourceEditor.remove(PREF_GREY_AMOUNT);
        }
        if (source.contains(PREF_DIM_AMOUNT)) {
            destinationEditor.putInt(PREF_DIM_AMOUNT, source.getInt(PREF_DIM_AMOUNT, 0));
            sourceEditor.remove(PREF_DIM_AMOUNT);
        }
        if (source.contains(PREF_BLUR_AMOUNT)) {
            destinationEditor.putInt(PREF_BLUR_AMOUNT, source.getInt(PREF_BLUR_AMOUNT, 0));
            sourceEditor.remove(PREF_BLUR_AMOUNT);
        }
        if (source.contains(PREF_DISABLE_BLUR_WHEN_LOCKED)) {
            destinationEditor.putBoolean(PREF_DISABLE_BLUR_WHEN_LOCKED,
                    source.getBoolean(PREF_DISABLE_BLUR_WHEN_LOCKED, false));
            sourceEditor.remove(PREF_DISABLE_BLUR_WHEN_LOCKED);
        }
        sourceEditor.putBoolean(PREF_MIGRATED, true);
        sourceEditor.apply();
        destinationEditor.apply();
    }

    private Prefs() {
    }
}
