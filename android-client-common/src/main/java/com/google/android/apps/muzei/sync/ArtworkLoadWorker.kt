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
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.internal.ProtocolConstants
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_MAX_LOADED_ARTWORK_ID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_RECENT_ARTWORK_IDS
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_REQUEST_LOAD
import com.google.android.apps.muzei.api.internal.getRecentIds
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.render.isValidImage
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import com.google.android.apps.muzei.util.getLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Worker responsible for loading artwork from a [MuzeiArtProvider] and inserting it into
 * the [MuzeiDatabase].
 */
class ArtworkLoadWorker(
        context: Context,
        workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ArtworkLoad"
        private const val PERIODIC_TAG = "ArtworkLoadPeriodic"
        private const val ARTWORK_LOAD_THROTTLE = 250L // quarter second

        internal fun enqueueNext(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniqueWork(TAG, ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ArtworkLoadWorker>().build())
        }

        internal fun enqueuePeriodic(
                context: Context,
                loadFrequencySeconds: Long,
                loadOnWifi: Boolean
        ) {
            val workManager = WorkManager.getInstance(context)
            workManager.enqueueUniquePeriodicWork(PERIODIC_TAG, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
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

        fun cancelPeriodic(context: Context) {
            val workManager = WorkManager.getInstance(context)
            workManager.cancelUniqueWork(PERIODIC_TAG)
        }
    }

    override suspend fun doWork() = withContext(syncSingleThreadContext) {
        // Throttle artwork loads
        delay(ARTWORK_LOAD_THROTTLE)
        // Now actually load the artwork
        val database = MuzeiDatabase.getInstance(applicationContext)
        val (authority) = database.providerDao()
                .getCurrentProvider() ?: return@withContext Result.failure()
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Artwork Load for $authority")
        }
        val contentUri = ProviderContract.getContentUri(authority)
        try {
            ContentProviderClientCompat.getClient(applicationContext, contentUri)?.use { client ->
                val result = client.call(METHOD_GET_LOAD_INFO)
                        ?: return@withContext Result.failure()
                val maxLoadedArtworkId = result.getLong(KEY_MAX_LOADED_ARTWORK_ID, 0L)
                val recentArtworkIds = result.getRecentIds(KEY_RECENT_ARTWORK_IDS)
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
                                validArtwork.providerAuthority = authority
                                val artworkId = database.artworkDao().insert(validArtwork)
                                if (BuildConfig.DEBUG) {
                                    Log.d(TAG, "Loaded ${validArtwork.imageUri} into id $artworkId")
                                }
                                client.call(METHOD_MARK_ARTWORK_LOADED, validArtwork.imageUri.toString())
                                // If we just loaded the last new artwork, we should request that they load another
                                // in preparation for the next load
                                if (!newArtwork.moveToNext()) {
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Out of new artwork, requesting load from $authority")
                                    }
                                    client.call(METHOD_REQUEST_LOAD)
                                }
                                return@withContext Result.success()
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Could not find any new artwork, requesting load from $authority")
                        }
                        // No new artwork, request that they load another in preparation for the next load
                        client.call(METHOD_REQUEST_LOAD)
                        // Is there any artwork at all?
                        if (allArtwork.count == 0) {
                            Log.w(TAG, "Unable to find any artwork for $authority")
                            return@withContext Result.failure()
                        }
                        // Okay so there's at least some artwork.
                        // Is it just the one artwork we're already showing?
                        val currentArtwork = database.artworkDao().getCurrentArtwork()
                        if (allArtwork.count == 1 && allArtwork.moveToFirst()) {
                            val artworkId = allArtwork.getLong(BaseColumns._ID)
                            val artworkUri = ContentUris.withAppendedId(contentUri, artworkId)
                            if (artworkUri == currentArtwork?.imageUri) {
                                if (BuildConfig.DEBUG) {
                                    Log.i(TAG, "Provider $authority only has one artwork")
                                }
                                return@withContext Result.failure()
                            }
                        }
                        // At this point, we know there must be some artwork that isn't the current
                        // artwork. We want to avoid showing artwork we've recently loaded, so
                        // we'll generate two sequences - the first being made up of
                        // non recent artwork, the second being made up of only recent artwork
                        // Build a lambda that checks whether the given position
                        // represents the current artwork
                        val isCurrentArtwork: (position: Int) -> Boolean = { position ->
                            if (allArtwork.moveToPosition(position)) {
                                val artworkId = allArtwork.getLong(BaseColumns._ID)
                                val artworkUri = ContentUris.withAppendedId(contentUri, artworkId)
                                artworkUri == currentArtwork?.imageUri
                            } else {
                                false
                            }
                        }
                        // Build a lambda that checks whether the given position
                        // represents an artwork in the recentArtworkIds
                        val isRecentArtwork: (position: Int) -> Boolean = { position ->
                            if (allArtwork.moveToPosition(position)) {
                                val artworkId = allArtwork.getLong(BaseColumns._ID)
                                recentArtworkIds.contains(artworkId)
                            } else {
                                false
                            }
                        }
                        // Now generate a random sequence for non recent artwork
                        val nonRecentArtworkSequence = generateSequence {
                            Random.nextInt(allArtwork.count)
                        }.distinct().take(allArtwork.count)
                            .filterNot(isCurrentArtwork)
                            .filterNot(isRecentArtwork)
                        // Now generate another sequence for recent artwork
                        val recentArtworkSequence = generateSequence {
                            Random.nextInt(allArtwork.count)
                        }.distinct().take(allArtwork.count)
                            .filterNot(isCurrentArtwork)
                            .filter(isRecentArtwork)
                        // And build the final sequence that iterates first through
                        // non recent artwork, then recent artwork
                        val randomSequence = nonRecentArtworkSequence + recentArtworkSequence
                        val iterator = randomSequence.iterator()
                        while (iterator.hasNext()) {
                            val position = iterator.next()
                            if (allArtwork.moveToPosition(position)) {
                                checkForValidArtwork(client, contentUri, allArtwork)?.apply {
                                    providerAuthority = authority
                                    val artworkId = database.artworkDao().insert(this)
                                    if (BuildConfig.DEBUG) {
                                        Log.d(TAG, "Loaded $imageUri into id $artworkId")
                                    }
                                    client.call(METHOD_MARK_ARTWORK_LOADED, imageUri.toString())
                                    return@withContext Result.success()
                                }
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            Log.i(TAG, "Unable to find any other valid artwork for $authority")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                else -> Log.i(TAG, "Provider $authority crashed while retrieving artwork: ${e.message}")
            }
        }
        Result.retry()
    }

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
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Artwork $artworkUri is not a valid image")
                    }
                    // Tell the client that the artwork is invalid
                    client.call(ProtocolConstants.METHOD_MARK_ARTWORK_INVALID, artworkUri.toString())
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "Unable to preload artwork $artworkUri: ${e.message}")
        } catch (e: Exception) {
            when (e) {
                is CancellationException -> throw e
                else -> Log.i(TAG, "Provider ${contentUri.authority} crashed preloading artwork " +
                        "$artworkUri: ${e.message}")
            }
        }

        return null
    }
}