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

package com.google.android.apps.muzei.sync

import android.content.ContentUris
import android.database.Cursor
import android.net.Uri
import android.os.RemoteException
import android.provider.BaseColumns
import android.util.Log
import androidx.core.database.getLong
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_MAX_LOADED_ARTWORK_ID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_RECENT_ARTWORK_IDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD
import com.google.android.apps.muzei.api.internal.RecentArtworkIdsConverter
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.render.isValidImage
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.newSingleThreadContext
import kotlinx.coroutines.experimental.runBlocking
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.IOException
import java.util.Random
import java.util.concurrent.TimeUnit

/**
 * Worker responsible for loading artwork from a [MuzeiArtProvider] and inserting it into
 * the [MuzeiDatabase].
 */
class ArtworkLoadWorker : Worker() {

    companion object {
        private const val TAG = "ArtworkLoad"
        private const val PERIODIC_TAG = "ArtworkLoadPeriodic"
        private const val ARTWORK_LOAD_THROTTLE = 250 // quarter second
        private val singleThreadContext by lazy {
            newSingleThreadContext(TAG)
        }

        internal fun enqueueNext() {
            val workManager = WorkManager.getInstance() ?: return
            workManager.beginUniqueWork(TAG, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ArtworkLoadWorker>().build())
                    .enqueue()
        }

        internal fun enqueuePeriodic(
                loadFrequencySeconds: Long,
                loadOnWifi: Boolean
        ) {
            val workManager = WorkManager.getInstance() ?: return
            workManager.enqueueUniquePeriodicWork(PERIODIC_TAG, ExistingPeriodicWorkPolicy.REPLACE,
                    PeriodicWorkRequestBuilder<ArtworkLoadWorker>(
                            loadFrequencySeconds, TimeUnit.SECONDS,
                            loadFrequencySeconds / 10, TimeUnit.SECONDS)
                            .setConstraints(Constraints.Builder()
                                    .setRequiredNetworkType(if (loadOnWifi) {
                                        NetworkType.UNMETERED
                                    } else {
                                        NetworkType.CONNECTED
                                    })
                                    .build())
                            .build())
        }

        fun cancelPeriodic() {
            val workManager = WorkManager.getInstance() ?: return
            workManager.cancelUniqueWork(PERIODIC_TAG)
        }
    }

    override fun doWork() = runBlocking(singleThreadContext) {
        // Throttle artwork loads
        delay(ARTWORK_LOAD_THROTTLE)
        loadArtwork()
    }

    private suspend fun loadArtwork(): Result {
        val database = MuzeiDatabase.getInstance(applicationContext)
        val (componentName) = database.providerDao()
                .getCurrentProvider() ?: return Result.FAILURE
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Artwork Load for $componentName")
        }
        val contentUri = MuzeiArtProvider.getContentUri(applicationContext, componentName)
        try {
            ContentProviderClientCompat.getClient(applicationContext, contentUri)?.use { client ->
                val result = client.call(METHOD_GET_LOAD_INFO)
                        ?: return Result.FAILURE
                val maxLoadedArtworkId = result.getLong(KEY_MAX_LOADED_ARTWORK_ID, 0L)
                val recentArtworkIds = RecentArtworkIdsConverter.fromString(
                        result.getString(KEY_RECENT_ARTWORK_IDS, ""))
                client.query(
                        contentUri,
                        selection = "_id > ?",
                        selectionArgs = arrayOf(maxLoadedArtworkId.toString()),
                        sortOrder = ProviderContract.Artwork._ID
                )?.use { newArtwork ->
                    client.query(contentUri)?.use { allArtwork ->
                        // First prioritize new artwork
                        while (newArtwork.moveToNext()) {
                            val validArtwork = checkForValidArtwork(client, contentUri, newArtwork)
                            if (validArtwork != null) {
                                validArtwork.providerComponentName = componentName
                                val artworkId = database.artworkDao().insert(validArtwork)
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "Loaded ${validArtwork.imageUri} into id $artworkId")
                                }
                                client.call(METHOD_MARK_ARTWORK_LOADED, validArtwork.imageUri.toString())
                                // If we just loaded the last new artwork, we should request that they load another
                                // in preparation for the next load
                                if (!newArtwork.moveToNext()) {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Out of new artwork, requesting load from $componentName")
                                    }
                                    client.call(METHOD_REQUEST_LOAD)
                                }
                                return Result.SUCCESS
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Could not find any new artwork, requesting load from $componentName")
                        }
                        // No new artwork, request that they load another in preparation for the next load
                        client.call(METHOD_REQUEST_LOAD)
                        // Is there any artwork at all?
                        if (allArtwork.count == 0) {
                            Log.w(TAG, "Unable to find any artwork for $componentName")
                            return Result.FAILURE
                        }
                        // Okay so there's at least some artwork.
                        // Is it just the one artwork we're already showing?
                        if (allArtwork.count == 1 && allArtwork.moveToFirst()) {
                            val artworkId = allArtwork.getLong(BaseColumns._ID)
                            val artworkUri = ContentUris.withAppendedId(contentUri, artworkId)
                            val currentArtwork = database.artworkDao().getCurrentArtwork()
                            if (artworkUri == currentArtwork?.imageUri) {
                                if (BuildConfig.DEBUG) {
                                    Log.i(TAG, "Unable to find any other artwork for $componentName")
                                }
                                return Result.FAILURE
                            }
                        }
                        // At this point, we know there must be some artwork that isn't the current
                        // artwork. We want to avoid showing artwork we've recently loaded, but
                        // don't want to exclude *all* of the current artwork, so we cut down the
                        // recent list's size to avoid issues where the provider has deleted a
                        // large percentage of their artwork
                        while (recentArtworkIds.size > allArtwork.count / 2) {
                            recentArtworkIds.removeFirst()
                        }
                        // Now find a random piece of artwork that isn't in our previous list
                        val random = Random()
                        var remainingAttempts = allArtwork.count
                        while (remainingAttempts-- > 0) {
                            val position = random.nextInt(allArtwork.count)
                            if (allArtwork.moveToPosition(position)) {
                                var artworkId = allArtwork.getLong(BaseColumns._ID)
                                if (recentArtworkIds.contains(artworkId)) {
                                    // Skip previously selected artwork
                                    continue
                                }
                                checkForValidArtwork(client, contentUri, allArtwork)?.apply {
                                    providerComponentName = componentName
                                    artworkId = database.artworkDao().insert(this)
                                    if (BuildConfig.DEBUG) {
                                        val attempts = allArtwork.count - remainingAttempts
                                        Log.d(TAG, "Loaded $imageUri into id $artworkId, took "
                                                + "$attempts attempt" +
                                                if (attempts > 1) "s" else "")
                                    }
                                    client.call(METHOD_MARK_ARTWORK_LOADED, imageUri.toString())
                                    return Result.SUCCESS
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "Unable to find any other valid artwork for $componentName")
                        }
                    }
                }
            }
            return Result.FAILURE
        } catch (e: RemoteException) {
            Log.i(TAG, "Provider $componentName crashed while retrieving artwork", e)
            return Result.FAILURE
        }
    }

    @Throws(RemoteException::class)
    private suspend fun checkForValidArtwork(
            client: ContentProviderClientCompat,
            contentUri: Uri,
            data: Cursor
    ): Artwork? {
        val providerArtwork = com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data)
        val artworkUri = ContentUris.withAppendedId(contentUri, providerArtwork.id)
        try {
            client.openInputStream(artworkUri)?.use { inputStream ->
                if (inputStream.isValidImage()) {
                    return Artwork(artworkUri).apply {
                        title = providerArtwork.title
                        byline = providerArtwork.byline
                        attribution = providerArtwork.attribution
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to preload artwork $artworkUri", e)
        }

        return null
    }
}
