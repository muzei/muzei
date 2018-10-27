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

import android.arch.lifecycle.Observer
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.preference.PreferenceManager
import android.support.annotation.RequiresApi
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.crashlytics.android.Crashlytics
import com.google.android.apps.muzei.api.internal.ProtocolConstants
import com.google.android.apps.muzei.api.internal.ProtocolConstants.KEY_LAST_LOADED_TIME
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_GET_LOAD_INFO
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.render.isValidImage
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import kotlinx.coroutines.experimental.runBlocking
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.IOException
import java.util.HashSet
import java.util.concurrent.TimeUnit

/**
 * Worker responsible for setting up the recurring artwork load from a [MuzeiArtProvider] and
 * kicking off an immediate load if the current artwork is invalid or we're overdue for loading
 * artwork.
 */
class ProviderChangedWorker(
        context: Context,
        workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "ProviderChanged"
        private const val PERSISTENT_CHANGED_TAG = "persistent_changed"
        private const val EXTRA_CONTENT_URI = "content_uri"
        private const val PREF_PERSISTENT_LISTENERS = "persistentListeners"

        internal fun enqueueSelected() {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<ProviderChangedWorker>()
                    .setInputData(workDataOf(TAG to "selected"))
                    .build())
        }

        internal fun enqueueChanged() {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<ProviderChangedWorker>()
                    .setInputData(workDataOf(TAG to "changed"))
                    .build())
        }

        @RequiresApi(Build.VERSION_CODES.N)
        fun addPersistentListener(context: Context, name: String) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val persistentListeners = preferences.getStringSet(PREF_PERSISTENT_LISTENERS,
                    null) ?: HashSet()
            persistentListeners.add(name)
            preferences.edit {
                putStringSet(PREF_PERSISTENT_LISTENERS, persistentListeners)
            }
            startListening(context)
        }

        @RequiresApi(Build.VERSION_CODES.N)
        fun removePersistentListener(context: Context, name: String) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val persistentListeners = preferences.getStringSet(PREF_PERSISTENT_LISTENERS,
                    null) ?: HashSet()
            persistentListeners.remove(name)
            preferences.edit {
                putStringSet(PREF_PERSISTENT_LISTENERS, persistentListeners)
            }
            if (persistentListeners.isEmpty()) {
                cancelObserver()
            }
        }

        @RequiresApi(Build.VERSION_CODES.N)
        internal fun activeListeningStateChanged(context: Context, listening: Boolean) {
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val persistentListeners = preferences.getStringSet(PREF_PERSISTENT_LISTENERS,
                    HashSet()) ?: HashSet()
            if (!persistentListeners.isEmpty()) {
                if (listening) {
                    cancelObserver()
                } else {
                    startListening(context)
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.N)
        private fun startListening(context: Context) {
            val providerManager = ProviderManager.getInstance(context)
            if (!providerManager.hasActiveObservers()) {
                val providerLiveData = MuzeiDatabase.getInstance(context).providerDao()
                        .currentProvider
                providerLiveData.observeForever(
                        object : Observer<Provider?> {
                            override fun onChanged(provider: Provider?) {
                                if (provider == null) {
                                    // Keep listening until there's an active Provider
                                    return
                                }
                                providerLiveData.removeObserver(this)
                                // Make sure we're still not actively listening
                                if (!providerManager.hasActiveObservers()) {
                                    val contentUri = ProviderContract.Artwork.getContentUri(
                                            provider.authority)
                                    scheduleObserver(contentUri)
                                }
                            }
                        })
            }
        }

        @RequiresApi(Build.VERSION_CODES.N)
        private fun scheduleObserver(contentUri: Uri) {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<ProviderChangedWorker>()
                    .addTag(PERSISTENT_CHANGED_TAG)
                    .setInputData(workDataOf(
                            TAG to PERSISTENT_CHANGED_TAG,
                            EXTRA_CONTENT_URI to contentUri.toString()))
                    .setConstraints(Constraints.Builder()
                            .addContentUriTrigger(contentUri, true)
                            .build())
                    .build())
        }

        private fun cancelObserver() {
            val workManager = WorkManager.getInstance()
            workManager.cancelAllWorkByTag(PERSISTENT_CHANGED_TAG)
        }
    }

    override fun doWork() = runBlocking(syncSingleThreadContext) {
        val tag = inputData.getString(TAG) ?: ""
        // First schedule the observer to pick up any changes fired
        // by the work done in handleProviderChange
        if (tag == PERSISTENT_CHANGED_TAG &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            inputData.getString(EXTRA_CONTENT_URI)?.toUri()?.run {
                scheduleObserver(this)
            }
        }
        // Now actually handle the provider change
        handleProviderChange(tag)
    }

    private suspend fun handleProviderChange(tag: String): Result {
        val database = MuzeiDatabase.getInstance(applicationContext)
        val provider = database.providerDao()
                .getCurrentProvider() ?: return Result.FAILURE
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Provider Change ($tag) for ${provider.authority}")
        }
        val contentUri = ProviderContract.Artwork.getContentUri(provider.authority)
        try {
            ContentProviderClientCompat.getClient(applicationContext, contentUri)?.use { client ->
                val result = client.call(METHOD_GET_LOAD_INFO)
                        ?: return Result.RETRY
                val lastLoadedTime = result.getLong(KEY_LAST_LOADED_TIME, 0L)
                client.query(contentUri)?.use { allArtwork ->
                    val providerManager = ProviderManager.getInstance(applicationContext)
                    val loadFrequencySeconds = providerManager.loadFrequencySeconds
                    val shouldSchedule = loadFrequencySeconds > 0
                    val overDue = shouldSchedule &&
                            System.currentTimeMillis() - lastLoadedTime >= TimeUnit.SECONDS.toMillis(loadFrequencySeconds)
                    val enqueueNext = overDue || !isCurrentArtworkValid(client, provider)
                    if (enqueueNext) {
                        // Schedule an immediate load
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Scheduling an immediate load")
                        }
                        ArtworkLoadWorker.enqueueNext()
                    }
                    if (shouldSchedule) {
                        // Schedule the periodic work
                        ArtworkLoadWorker.enqueuePeriodic(loadFrequencySeconds,
                                providerManager.loadOnWifi)
                    } else {
                        // Clear any existing recurring work as it isn't needed anymore
                        ArtworkLoadWorker.cancelPeriodic()
                    }
                    // Update whether the provider supports the 'Next Artwork' button
                    var validArtworkCount = 0
                    while (allArtwork.moveToNext()) {
                        if (isValidArtwork(client, contentUri, allArtwork)) {
                            validArtworkCount++
                        }
                        if (validArtworkCount > 1) {
                            break
                        }
                    }
                    provider.supportsNextArtwork = validArtworkCount > 1
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Found at least $validArtworkCount artwork for $provider")
                    }
                    database.providerDao().update(provider)
                    if (validArtworkCount <= 1 && !enqueueNext) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Requesting a load from $provider")
                        }
                        // Request a load if we don't have any more artwork
                        // and haven't just called enqueueNext
                        client.call(ProtocolConstants.METHOD_REQUEST_LOAD)
                    }
                    return Result.SUCCESS
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "Provider ${provider.authority} crashed while retrieving artwork: ${e.message}")
            Crashlytics.setString("provider", provider.authority)
            Crashlytics.logException(e)
        }
        return Result.RETRY
    }

    private suspend fun isCurrentArtworkValid(
            client: ContentProviderClientCompat,
            provider: Provider
    ): Boolean {
        MuzeiDatabase.getInstance(applicationContext).artworkDao()
                .getCurrentArtworkForProvider(provider.authority)?.let { artwork ->
                    client.query(artwork.imageUri)?.use { cursor ->
                        val contentUri = ProviderContract.Artwork.getContentUri(provider.authority)
                        return cursor.moveToNext() && isValidArtwork(client, contentUri, cursor)
                    }
                }
        return false
    }

    private suspend fun isValidArtwork(
            client: ContentProviderClientCompat,
            contentUri: Uri,
            data: Cursor
    ): Boolean {
        val providerArtwork = com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data)
        val artworkUri = ContentUris.withAppendedId(contentUri, providerArtwork.id)
        try {
            client.openInputStream(artworkUri)?.use { inputStream ->
                if (inputStream.isValidImage()) {
                    return true
                } else {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Artwork $artworkUri is not a valid image")
                    }
                    // Tell the client that the artwork is invalid
                    client.call(ProtocolConstants.METHOD_MARK_ARTWORK_INVALID, artworkUri.toString())
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Unable to preload artwork $artworkUri: ${e.message}")
        } catch (e: RemoteException) {
            Log.w(TAG, "Provider ${contentUri.authority} crashed preloading artwork " +
                    "$artworkUri: ${e.message}")
        }

        return false
    }
}
