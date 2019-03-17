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

package com.google.android.apps.muzei.complications

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.preference.PreferenceManager
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.ComplicationManager
import android.support.wearable.complications.ComplicationProviderService
import android.support.wearable.complications.ComplicationText
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.TaskStackBuilder
import androidx.core.content.edit
import androidx.core.os.bundleOf
import com.google.android.apps.muzei.FullScreenActivity
import com.google.android.apps.muzei.ProviderChangedReceiver
import com.google.android.apps.muzei.datalayer.ActivateMuzeiIntentService
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.ProviderChangedWorker
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import java.util.TreeSet

/**
 * Provide Muzei backgrounds to other watch faces
 */
@RequiresApi(Build.VERSION_CODES.N)
class ArtworkComplicationProviderService : ComplicationProviderService() {

    companion object {
        private const val TAG = "ArtworkComplProvider"

        internal const val KEY_COMPLICATION_IDS = "complication_ids"
    }

    override fun onCreate() {
        super.onCreate()
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
    }

    override fun onComplicationActivated(complicationId: Int, type: Int, manager: ComplicationManager) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Activated $complicationId")
        }
        addComplication(complicationId)
        FirebaseAnalytics.getInstance(this).logEvent("complication_artwork_activated", bundleOf(
                FirebaseAnalytics.Param.CONTENT_TYPE to type.toString()))
        ProviderChangedWorker.addPersistentListener(this, "complication_artwork")
        ProviderChangedReceiver.onVisibleChanged(this)
    }

    private fun addComplication(complicationId: Int) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val complications = preferences.getStringSet(KEY_COMPLICATION_IDS,
                null) ?: TreeSet()
        complications.add(Integer.toString(complicationId))
        preferences.edit { putStringSet(KEY_COMPLICATION_IDS, complications) }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "addComplication: $complications")
        }
        ArtworkComplicationWorker.scheduleComplicationUpdate()
    }

    override fun onComplicationDeactivated(complicationId: Int) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Deactivated $complicationId")
        }
        FirebaseAnalytics.getInstance(this).logEvent("complication_artwork_deactivated", null)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val complications = preferences.getStringSet(KEY_COMPLICATION_IDS,
                null) ?: TreeSet()
        complications.remove(Integer.toString(complicationId))
        preferences.edit { putStringSet(KEY_COMPLICATION_IDS, complications) }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Current complications: $complications")
        }
        if (complications.isEmpty()) {
            ArtworkComplicationWorker.cancelComplicationUpdate()
            ProviderChangedWorker.removePersistentListener(this, "complication_artwork")
            ProviderChangedReceiver.onVisibleChanged(this)
        }
    }

    override fun onComplicationUpdate(
            complicationId: Int,
            type: Int,
            complicationManager: ComplicationManager
    ) {
        // Make sure that the complicationId is really in our set of added complications
        // This fixes corner cases like Muzei being uninstalled and reinstalled
        // (which wipes out our SharedPreferences but keeps any complications activated)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val complications = preferences.getStringSet(KEY_COMPLICATION_IDS,
                null) ?: TreeSet()
        if (!complications.contains(complicationId.toString())) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Update missing id $complicationId")
            }
            addComplication(complicationId)
        }
        val applicationContext = applicationContext
        GlobalScope.launch {
            val database = MuzeiDatabase.getInstance(applicationContext)
            val provider = database.providerDao().getCurrentProvider()
            if (provider == null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Update no provider for $complicationId")
                }
                ProviderManager.select(applicationContext, FEATURED_ART_AUTHORITY)
                ActivateMuzeiIntentService.checkForPhoneApp(applicationContext)
                complicationManager.updateComplicationData(complicationId,
                        ComplicationData.Builder(ComplicationData.TYPE_NO_DATA).build())
                return@launch
            }
            val artwork = database.artworkDao().getCurrentArtwork()
            if (artwork == null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Update no artwork for $complicationId")
                }
                complicationManager.updateComplicationData(complicationId,
                        ComplicationData.Builder(ComplicationData.TYPE_NO_DATA).build())
                return@launch
            }
            val builder = ComplicationData.Builder(type).apply {
                val intent = Intent(applicationContext, FullScreenActivity::class.java)
                val taskStackBuilder = TaskStackBuilder.create(applicationContext)
                        .addNextIntentWithParentStack(intent)
                val tapAction = taskStackBuilder.getPendingIntent(0, 0)
                when (type) {
                    ComplicationData.TYPE_LONG_TEXT -> {
                        val title = artwork.title
                        val byline = artwork.byline
                        if (title.isNullOrBlank() && byline.isNullOrBlank()) {
                            // Both are empty so we don't have any data to show
                            complicationManager.updateComplicationData(complicationId,
                                    ComplicationData.Builder(ComplicationData.TYPE_NO_DATA).build())
                            return@launch
                        } else if (title.isNullOrBlank()) {
                            // We only have the byline, so use that as the long text
                            setLongText(ComplicationText.plainText(byline))
                        } else {
                            if (!byline.isNullOrBlank()) {
                                setLongTitle(ComplicationText.plainText(byline))
                            }
                            setLongText(ComplicationText.plainText(title))
                        }
                        setTapAction(tapAction)
                    }
                    ComplicationData.TYPE_SMALL_IMAGE -> {
                        setImageStyle(ComplicationData.IMAGE_STYLE_PHOTO)
                                .setSmallImage(Icon.createWithContentUri(artwork.contentUri))
                        setTapAction(tapAction)
                    }
                    ComplicationData.TYPE_LARGE_IMAGE -> setLargeImage(Icon.createWithContentUri(artwork.contentUri))
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Updated $complicationId")
                }
            }
            complicationManager.updateComplicationData(complicationId, builder.build())
        }
    }
}
