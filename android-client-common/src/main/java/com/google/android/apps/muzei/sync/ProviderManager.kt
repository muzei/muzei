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

import android.annotation.SuppressLint
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.androidclientcommon.BuildConfig

/**
 * Manager which monitors the current Provider
 */
class ProviderManager private constructor(private val context: Context)
    : MutableLiveData<Provider>(), Observer<Provider?> {

    companion object {
        private const val TAG = "ProviderManager"
        private const val PREF_LOAD_FREQUENCY_SECONDS = "loadFrequencySeconds"
        private const val DEFAULT_LOAD_FREQUENCY_SECONDS = -1L // By default, never auto-load

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ProviderManager? = null

        fun getInstance(context: Context): ProviderManager =
                instance ?: synchronized(this) {
                    instance ?: ProviderManager(context.applicationContext)
                            .also { instance = it }
                }
    }

    private val contentObserver: ContentObserver
    private val providerLiveData by lazy {
        MuzeiDatabase.getInstance(context).providerDao().currentProvider
    }
    private val artworkLiveData by lazy {
        MuzeiDatabase.getInstance(context).artworkDao().currentArtwork
    }
    private var nextArtworkJob: Job? = null
    private val artworkObserver = Observer<Artwork?> { artwork ->
        if (artwork == null) {
            // Can't have no artwork at all,
            // try loading the next artwork with a slight delay
            nextArtworkJob?.cancel()
            nextArtworkJob = launch {
                delay(1000)
                if (nextArtworkJob?.isCancelled == false) {
                    nextArtwork()
                }
            }
        } else {
            nextArtworkJob?.cancel()
        }
    }


    internal val loadFrequencySeconds: Long
        get() = PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(PREF_LOAD_FREQUENCY_SECONDS, DEFAULT_LOAD_FREQUENCY_SECONDS)

    init {
        contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "onChange for $uri")
                }
                ProviderChangedWorker.enqueueChanged()
            }
        }
    }

    override fun onActive() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ProviderManager became active")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ProviderChangedWorker.activeListeningStateChanged(context, true)
        }
        providerLiveData.observeForever(this)
        artworkLiveData.observeForever(artworkObserver)
        startArtworkLoad()
    }

    private fun startArtworkLoad() {
        val currentSource = value ?: return
        if (hasActiveObservers()) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Starting artwork load")
            }
            ProviderChangedWorker.enqueueSelected()
            // Listen for MuzeiArtProvider changes
            val contentUri = MuzeiArtProvider.getContentUri(context, currentSource.componentName)
            context.contentResolver.registerContentObserver(
                    contentUri, true, contentObserver)
        }
    }

    override fun onChanged(provider: Provider?) {
        val existingProvider = value
        value = provider
        if (existingProvider == null || provider != null && provider.componentName != existingProvider.componentName) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Provider changed to ${provider?.componentName}")
            }
            startArtworkLoad()
        }
    }

    override fun onInactive() {
        nextArtworkJob?.cancel()
        artworkLiveData.removeObserver(artworkObserver)
        providerLiveData.removeObserver(this)
        context.contentResolver.unregisterContentObserver(contentObserver)
        ArtworkLoadWorker.cancelPeriodic()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ProviderChangedWorker.activeListeningStateChanged(context, false)
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ProviderManager is now inactive")
        }
    }

    fun nextArtwork() {
        ArtworkLoadWorker.enqueueNext()
    }
}
