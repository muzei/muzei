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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.content.edit
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.delay
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.newSingleThreadContext
import net.nurik.roman.muzei.androidclientcommon.BuildConfig

/**
 * Single threaded coroutine context used for all sync operations
 */
internal val syncSingleThreadContext by lazy {
    newSingleThreadContext("ProviderSync")
}

/**
 * Manager which monitors the current Provider
 */
class ProviderManager private constructor(private val context: Context)
    : MutableLiveData<Provider>(), Observer<Provider?> {

    companion object {
        private const val TAG = "ProviderManager"
        private const val PREF_LOAD_FREQUENCY_SECONDS = "loadFrequencySeconds"
        private const val DEFAULT_LOAD_FREQUENCY_SECONDS = 3600L
        private const val PREF_LOAD_ON_WIFI = "loadOnWifi"
        private const val DEFAULT_LOAD_ON_WIFI = false

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: ProviderManager? = null

        fun getInstance(context: Context): ProviderManager =
                instance ?: synchronized(this) {
                    instance ?: ProviderManager(context.applicationContext)
                            .also { instance = it }
                }
    }

    private val packageChangeReceiver : BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val provider = value ?: return
            val packageName = intent?.data?.schemeSpecificPart
            if (provider.componentName.packageName == packageName) {
                // The selected provider changed,
                startArtworkLoad()
            }
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

    var loadFrequencySeconds: Long
        set(newLoadFrequency) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putLong(PREF_LOAD_FREQUENCY_SECONDS, newLoadFrequency)
            }
            if (newLoadFrequency > 0) {
                ArtworkLoadWorker.enqueuePeriodic(newLoadFrequency, loadOnWifi)
            } else {
                ArtworkLoadWorker.cancelPeriodic()
            }
        }
        get() = PreferenceManager.getDefaultSharedPreferences(context)
                .getLong(PREF_LOAD_FREQUENCY_SECONDS, DEFAULT_LOAD_FREQUENCY_SECONDS)

    var loadOnWifi: Boolean
        set(newLoadOnWifi) {
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putBoolean(PREF_LOAD_ON_WIFI, newLoadOnWifi)
            }
            if (loadFrequencySeconds > 0) {
                ArtworkLoadWorker.enqueuePeriodic(loadFrequencySeconds, newLoadOnWifi)
            }
        }
        get() = PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREF_LOAD_ON_WIFI, DEFAULT_LOAD_ON_WIFI)

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
        // Register for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        context.registerReceiver(packageChangeReceiver, packageChangeFilter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            ProviderChangedWorker.activeListeningStateChanged(context, true)
        }
        providerLiveData.observeForever(this)
        artworkLiveData.observeForever(artworkObserver)
        startArtworkLoad()
    }

    private fun runIfValid(provider: Provider?, block: (provider: Provider) -> Unit) {
        if (provider != null) {
            try {
                ProviderContract.Artwork.getContentUri(context, provider.componentName)
                // getContentUri succeeded, so it is a valid ContentProvider
                block(provider)
            } catch (e: IllegalArgumentException) {
                // Invalid ContentProvider, remove it from the ProviderDao
                launch {
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Invalid provider ${provider.componentName}", e)
                    }
                    MuzeiDatabase.getInstance(context).providerDao().delete(provider)
                }
            }
        }
    }

    private fun startArtworkLoad() {
        if (hasActiveObservers()) {
            runIfValid(value) { currentSource ->
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Starting artwork load")
                }
                // Listen for MuzeiArtProvider changes
                val contentUri = ProviderContract.Artwork.getContentUri(context, currentSource.componentName)
                context.contentResolver.registerContentObserver(
                        contentUri, true, contentObserver)
                ProviderChangedWorker.enqueueSelected()
            }
        }
    }

    override fun onChanged(newProvider: Provider?) {
        val existingProvider = value
        value = newProvider
        runIfValid(newProvider) { provider ->
            if (existingProvider == null || provider.componentName != existingProvider.componentName) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Provider changed to ${provider.componentName}")
                }
                startArtworkLoad()
            }
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
        context.unregisterReceiver(packageChangeReceiver)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "ProviderManager is now inactive")
        }
    }

    fun nextArtwork() {
        ArtworkLoadWorker.enqueueNext()
    }
}
