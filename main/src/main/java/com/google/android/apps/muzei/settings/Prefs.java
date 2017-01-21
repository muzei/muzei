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
import android.os.Build;
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
    private static final String PREF_MIGRATED_FROM_DEFAULT = "migrated_from_default";


    public static SharedPreferences getSharedPreferences(Context context) {
        Context deviceProtectedContext =
                ContextCompat.createDeviceProtectedStorageContext(context);
        SharedPreferences deviceProtectedSharedPreferences = deviceProtectedContext != null
                ? deviceProtectedContext.getSharedPreferences(WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE)
                : null;
        if (UserManagerCompat.isUserUnlocked(context)) {
            SharedPreferences defaultSharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(context);
            // First migrate the wallpaper settings to their own file
            if (!defaultSharedPreferences.getBoolean(PREF_MIGRATED_FROM_DEFAULT, false)) {
                SharedPreferences.Editor defaultEditor = defaultSharedPreferences.edit();
                SharedPreferences.Editor editor = context.getSharedPreferences(
                        WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE).edit();

                if (defaultSharedPreferences.contains(PREF_GREY_AMOUNT)) {
                    editor.putInt(PREF_GREY_AMOUNT,
                            defaultSharedPreferences.getInt(PREF_GREY_AMOUNT, 0));
                    defaultEditor.remove(PREF_GREY_AMOUNT);
                }
                if (defaultSharedPreferences.contains(PREF_DIM_AMOUNT)) {
                    editor.putInt(PREF_DIM_AMOUNT,
                            defaultSharedPreferences.getInt(PREF_DIM_AMOUNT, 0));
                    defaultEditor.remove(PREF_DIM_AMOUNT);
                }
                if (defaultSharedPreferences.contains(PREF_BLUR_AMOUNT)) {
                    editor.putInt(PREF_BLUR_AMOUNT,
                            defaultSharedPreferences.getInt(PREF_BLUR_AMOUNT, 0));
                    defaultEditor.remove(PREF_BLUR_AMOUNT);
                }
                if (defaultSharedPreferences.contains(PREF_DISABLE_BLUR_WHEN_LOCKED)) {
                    editor.putBoolean(PREF_DISABLE_BLUR_WHEN_LOCKED,
                            defaultSharedPreferences.getBoolean(PREF_DISABLE_BLUR_WHEN_LOCKED, false));
                    defaultEditor.remove(PREF_DISABLE_BLUR_WHEN_LOCKED);
                }
                defaultEditor.putBoolean(PREF_MIGRATED_FROM_DEFAULT, true);
                defaultEditor.apply();
                editor.apply();
            }
        }
        // Now migrate the file to device protected storage if available
        if (UserManagerCompat.isUserUnlocked(context) && deviceProtectedContext != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            deviceProtectedContext.moveSharedPreferencesFrom(context, WALLPAPER_PREFERENCES_NAME);
        }
        // Now open the correct SharedPreferences
        Context contextToUse = deviceProtectedContext != null ? deviceProtectedContext : context;
        return contextToUse.getSharedPreferences(WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private Prefs() {
    }
}
