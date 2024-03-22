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

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.TaskStackBuilder
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PhotoImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.google.android.apps.muzei.FullScreenActivity
import com.google.android.apps.muzei.ProviderChangedReceiver
import com.google.android.apps.muzei.datalayer.ActivateMuzeiReceiver
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.sync.ProviderChangedWorker
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import net.nurik.roman.muzei.BuildConfig
import java.util.TreeSet
import net.nurik.roman.muzei.androidclientcommon.R as CommonR

/**
 * Provide Muzei backgrounds to other watch faces
 */
@RequiresApi(Build.VERSION_CODES.N)
class ArtworkComplicationProviderService : SuspendingComplicationDataSourceService() {

    companion object {
        private const val TAG = "ArtworkComplProvider"

        internal const val KEY_COMPLICATION_IDS = "complication_ids"
    }

    override fun onCreate() {
        super.onCreate()
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
    }

    override fun onComplicationActivated(complicationInstanceId: Int, type: ComplicationType) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Activated $complicationInstanceId")
        }
        addComplication(complicationInstanceId)
        Firebase.analytics.logEvent("complication_artwork_activated") {
            param(FirebaseAnalytics.Param.CONTENT_TYPE, type.toString())
        }
        ProviderChangedWorker.addPersistentListener(this, "complication_artwork")
        ProviderChangedReceiver.onVisibleChanged(this)
    }

    private fun addComplication(complicationId: Int) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val complications = TreeSet(preferences.getStringSet(KEY_COMPLICATION_IDS,
                null) ?: emptySet())
        complications.add(complicationId.toString())
        preferences.edit { putStringSet(KEY_COMPLICATION_IDS, complications) }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "addComplication: $complications")
        }
        ArtworkComplicationWorker.scheduleComplicationUpdate(this)
    }

    override fun onComplicationDeactivated(complicationInstanceId: Int) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Deactivated $complicationInstanceId")
        }
        Firebase.analytics.logEvent("complication_artwork_deactivated", null)
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val complications = TreeSet(preferences.getStringSet(KEY_COMPLICATION_IDS,
                null) ?: emptySet())
        complications.remove(complicationInstanceId.toString())
        preferences.edit { putStringSet(KEY_COMPLICATION_IDS, complications) }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Current complications: $complications")
        }
        if (complications.isEmpty()) {
            ArtworkComplicationWorker.cancelComplicationUpdate(this)
            ProviderChangedWorker.removePersistentListener(this, "complication_artwork")
            ProviderChangedReceiver.onVisibleChanged(this)
        }
    }

    override fun getPreviewData(type: ComplicationType) = null

    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData {
        val complicationId = request.complicationInstanceId
        val type = request.complicationType
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
        val database = MuzeiDatabase.getInstance(applicationContext)
        val provider = database.providerDao().getCurrentProvider()
        if (provider == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Update no provider for $complicationId")
            }
            ProviderManager.select(applicationContext, FEATURED_ART_AUTHORITY)
            ActivateMuzeiReceiver.checkForPhoneApp(applicationContext)
            return NoDataComplicationData()
        }
        val artwork = database.artworkDao().getCurrentArtwork()
        if (artwork == null) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Update no artwork for $complicationId")
            }
            return NoDataComplicationData()
        }
        val title = artwork.title
        val byline = artwork.byline
        // Use the byline by default, falling back to the title
        val primaryText = when {
            title.isNullOrBlank() && !byline.isNullOrBlank() -> byline
            !title.isNullOrBlank() -> title
            else -> null
        }
        // Use the title as the secondary text only if the byline
        // is the primary text and there is a title
        val secondaryText = when {
            primaryText == byline && !title.isNullOrBlank() -> title
            else -> null
        }
        val contentDescription = primaryText ?: secondaryText ?: getString(CommonR.string.app_name)
        val intent = Intent(applicationContext, FullScreenActivity::class.java)
        val taskStackBuilder = TaskStackBuilder.create(applicationContext)
            .addNextIntentWithParentStack(intent)
        val tapAction = taskStackBuilder.getPendingIntent(0, PendingIntent.FLAG_IMMUTABLE)
        return when (type) {
            ComplicationType.LONG_TEXT -> {
                if (primaryText == null) {
                    // We don't have any text to show
                    NoDataComplicationData()
                } else {
                    LongTextComplicationData.Builder(
                        PlainComplicationText.Builder(primaryText).build(),
                        PlainComplicationText.Builder(contentDescription).build()
                    ).apply {
                        if (secondaryText != null) {
                            setTitle(PlainComplicationText.Builder(secondaryText).build())
                        }
                    }.setTapAction(tapAction).build()
                }
            }
            ComplicationType.SMALL_IMAGE -> {
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(
                        Icon.createWithContentUri(artwork.contentUri),
                        SmallImageType.PHOTO
                    ).build(),
                    PlainComplicationText.Builder(contentDescription).build()
                ).setTapAction(tapAction).build()
            }
            ComplicationType.PHOTO_IMAGE -> {
                PhotoImageComplicationData.Builder(
                    Icon.createWithContentUri(artwork.contentUri),
                    PlainComplicationText.Builder(contentDescription).build()
                ).setTapAction(tapAction).build()
            }
            else -> NoDataComplicationData()
        }
    }
}