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

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import android.support.v4.content.ContextCompat
import android.support.v4.os.UserManagerCompat
import androidx.core.content.edit

/**
 * Preference constants/helpers.
 */
object Prefs {
    const val PREF_GREY_AMOUNT = "grey_amount"
    const val PREF_DIM_AMOUNT = "dim_amount"
    const val PREF_BLUR_AMOUNT = "blur_amount"
    const val PREF_LOCK_GREY_AMOUNT = "lock_grey_amount"
    const val PREF_LOCK_DIM_AMOUNT = "lock_dim_amount"
    const val PREF_LOCK_BLUR_AMOUNT = "lock_blur_amount"
    const val PREF_LINK_EFFECTS = "link_effects"
    private const val PREF_DISABLE_BLUR_WHEN_LOCKED = "disable_blur_when_screen_locked_enabled"

    private const val WALLPAPER_PREFERENCES_NAME = "wallpaper_preferences"
    private const val PREF_MIGRATED = "migrated_from_default"

    @Synchronized
    fun getSharedPreferences(context: Context): SharedPreferences {
        val deviceProtectedContext = ContextCompat.createDeviceProtectedStorageContext(context)
        if (UserManagerCompat.isUserUnlocked(context)) {
            // First migrate the wallpaper settings to their own file
            val defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val wallpaperPreferences = context.getSharedPreferences(
                    WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE)
            migratePreferences(defaultSharedPreferences, wallpaperPreferences)

            // Now migrate the file to device protected storage if available
            if (deviceProtectedContext != null) {
                val deviceProtectedPreferences = deviceProtectedContext.getSharedPreferences(
                        WALLPAPER_PREFERENCES_NAME, Context.MODE_PRIVATE)
                migratePreferences(wallpaperPreferences, deviceProtectedPreferences)
            }
        }
        // Now open the correct SharedPreferences
        val contextToUse = deviceProtectedContext ?: context
        return contextToUse.getSharedPreferences(WALLPAPER_PREFERENCES_NAME,
                Context.MODE_PRIVATE).also { sp ->
            if (sp.contains(PREF_DISABLE_BLUR_WHEN_LOCKED)) {
                sp.edit {
                    val disableBlurWhenLocked = sp.getBoolean(
                            PREF_DISABLE_BLUR_WHEN_LOCKED, false)
                    if (sp.contains(PREF_GREY_AMOUNT)) {
                        val greyAmount = sp.getInt(PREF_GREY_AMOUNT, 0)
                        putInt(PREF_LOCK_GREY_AMOUNT,
                                if (disableBlurWhenLocked) 0 else greyAmount)
                    } else if (disableBlurWhenLocked) {
                        putInt(PREF_LOCK_GREY_AMOUNT, 0)
                    }
                    if (sp.contains(PREF_DIM_AMOUNT)) {
                        val dimAmount = sp.getInt(PREF_DIM_AMOUNT, 0)
                        putInt(PREF_LOCK_DIM_AMOUNT,
                                if (disableBlurWhenLocked) 0 else dimAmount)
                    } else if (disableBlurWhenLocked) {
                        putInt(PREF_LOCK_DIM_AMOUNT, 0)
                    }
                    if (sp.contains(PREF_BLUR_AMOUNT)) {
                        val blurAmount = sp.getInt(PREF_BLUR_AMOUNT, 0)
                        putInt(PREF_LOCK_BLUR_AMOUNT,
                                if (disableBlurWhenLocked) 0 else blurAmount)
                    } else if (disableBlurWhenLocked) {
                        putInt(PREF_LOCK_BLUR_AMOUNT, 0)
                    }
                    putBoolean(PREF_LINK_EFFECTS, !disableBlurWhenLocked)
                    remove(PREF_DISABLE_BLUR_WHEN_LOCKED)
                }
            }
        }
    }

    private fun migratePreferences(source: SharedPreferences, destination: SharedPreferences) {
        if (source.getBoolean(PREF_MIGRATED, false)) {
            return
        }
        val sourceEditor = source.edit()
        val destinationEditor = destination.edit()

        val disableBlurWhenLocked = source.getBoolean(
                PREF_DISABLE_BLUR_WHEN_LOCKED, false)
        sourceEditor.remove(PREF_DISABLE_BLUR_WHEN_LOCKED)
        if (source.contains(PREF_GREY_AMOUNT)) {
            val greyAmount = source.getInt(PREF_GREY_AMOUNT, 0)
            destinationEditor.putInt(PREF_GREY_AMOUNT, greyAmount)
            destinationEditor.putInt(PREF_LOCK_GREY_AMOUNT,
                    if (disableBlurWhenLocked) 0 else greyAmount)
            sourceEditor.remove(PREF_GREY_AMOUNT)
        } else if (disableBlurWhenLocked) {
            destinationEditor.putInt(PREF_LOCK_GREY_AMOUNT, 0)
        }
        if (source.contains(PREF_DIM_AMOUNT)) {
            val dimAmount = source.getInt(PREF_DIM_AMOUNT, 0)
            destinationEditor.putInt(PREF_DIM_AMOUNT, dimAmount)
            destinationEditor.putInt(PREF_LOCK_DIM_AMOUNT,
                    if (disableBlurWhenLocked) 0 else dimAmount)
            sourceEditor.remove(PREF_DIM_AMOUNT)
        } else if (disableBlurWhenLocked) {
            destinationEditor.putInt(PREF_LOCK_DIM_AMOUNT, 0)
        }
        if (source.contains(PREF_BLUR_AMOUNT)) {
            val blurAmount = source.getInt(PREF_BLUR_AMOUNT, 0)
            destinationEditor.putInt(PREF_BLUR_AMOUNT, blurAmount)
            destinationEditor.putInt(PREF_LOCK_BLUR_AMOUNT,
                    if (disableBlurWhenLocked) 0 else blurAmount)
            sourceEditor.remove(PREF_BLUR_AMOUNT)
        } else if (disableBlurWhenLocked) {
            destinationEditor.putInt(PREF_LOCK_BLUR_AMOUNT, 0)
        }
        sourceEditor.putBoolean(PREF_MIGRATED, true)
        sourceEditor.apply()
        destinationEditor.apply()
    }
}
