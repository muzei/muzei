/*
 * Copyright 2019 Google Inc.
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

package com.google.android.apps.muzei.legacy

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.legacy.BuildConfig.LEGACY_AUTHORITY
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.collectIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion

suspend fun Provider?.allowsNextArtwork(context: Context): Boolean {
    return when {
        this == null -> false
        supportsNextArtwork -> true
        authority != LEGACY_AUTHORITY -> false
        else -> LegacySourceManager.getInstance(context).allowsNextArtwork()
    }
}

/**
 * Class responsible for managing interactions with legacy sources.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacySourceManager(private val applicationContext: Context) : DefaultLifecycleObserver {

    companion object {
        val LEARN_MORE_LINK =
                "https://medium.com/muzei/muzei-3-0-and-legacy-sources-8261979e2264".toUri()

        @Volatile
        private var instance: LegacySourceManager? = null

        fun getInstance(context: Context): LegacySourceManager {
            val applicationContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance
                        ?: LegacySourceManager(applicationContext).also { manager ->
                    instance = manager
                }
            }
        }
    }

    @SuppressLint("WrongConstant")
    private fun getService(): Flow<ComponentName?> = callbackFlow {
        val pm = applicationContext.packageManager
        // Create an IntentFilter for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        val queryAndSet = {
            @Suppress("DEPRECATION")
            trySend(pm.queryIntentServices(Intent(LegacySourceServiceProtocol.LEGACY_SOURCE_ACTION), 0)
                    .firstOrNull()
                    ?.serviceInfo
                    ?.run {
                        ComponentName(packageName, name)
                    })
        }
        val packageChangeReceiver : BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.data == null) {
                    return
                }
                queryAndSet()
            }
        }
        ContextCompat.registerReceiver(
            applicationContext,
            packageChangeReceiver,
            packageChangeFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Set the initial state
        queryAndSet()

        awaitClose {
            applicationContext.unregisterReceiver(packageChangeReceiver)
        }
    }

    private val legacySourcePackageListener = LegacySourcePackageListener(applicationContext)
    private val serviceConnection = LegacySourceServiceConnection(applicationContext)

    /**
     * The list of unsupported legacy sources. If the Muzei Legacy app is installed, this will
     * be an empty list (as those previously unsupported sources are supported via
     * the Muzei Legacy MuzeiArtProvider).
     */
    val unsupportedSources = getService().distinctUntilChanged().flatMapLatest { componentName ->
        if (componentName == null) {
            legacySourcePackageListener.unsupportedSources
        } else {
            flowOf(emptyList())
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        getService().distinctUntilChanged().onCompletion {
            serviceConnection.unbindService()
        }.collectIn(owner) { componentName ->
            if (componentName == null) {
                serviceConnection.unbindService()
            } else {
                serviceConnection.bindService(componentName)
            }
        }

        // Collect on the set of unsupported sources to ensure that we continue
        // to send unsupported sources notifications for the entire time the
        // Lifecycle is STARTED
        unsupportedSources.collectIn(owner)

        val database = MuzeiDatabase.getInstance(applicationContext)
        database.providerDao().getCurrentProviderFlow().collectIn(owner) { provider ->
            serviceConnection.onProviderChanged(provider)
        }
    }

    suspend fun nextArtwork() {
        val provider = MuzeiDatabase.getInstance(applicationContext)
                .providerDao().getCurrentProvider()
        if (provider?.authority == LEGACY_AUTHORITY) {
            serviceConnection.nextArtwork()
        } else {
            ProviderManager.getInstance(applicationContext).nextArtwork()
        }
    }

    suspend fun allowsNextArtwork() = serviceConnection.allowsNextArtwork()
}