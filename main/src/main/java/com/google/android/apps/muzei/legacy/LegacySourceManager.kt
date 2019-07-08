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

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.observe
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sync.ProviderManager
import net.nurik.roman.muzei.BuildConfig.LEGACY_AUTHORITY

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
class LegacySourceManager(private val applicationContext: Context) : DefaultLifecycleObserver {

    companion object {
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

    private val serviceLiveData = object : MutableLiveData<ComponentName?>() {
        val pm = applicationContext.packageManager
        // Create an IntentFilter for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        val packageChangeReceiver : BroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                if (intent?.data == null) {
                    return
                }
                queryAndSet()
            }
        }

        override fun onActive() {
            applicationContext.registerReceiver(packageChangeReceiver, packageChangeFilter)
            // Set the initial state
            queryAndSet()
        }

        fun queryAndSet() {
            value = pm.queryIntentServices(Intent(LegacySourceServiceProtocol.LEGACY_SOURCE_ACTION), 0)
                    .firstOrNull()
                    ?.serviceInfo
                    ?.run {
                        ComponentName(packageName, name)
                    }
        }

        override fun onInactive() {
            applicationContext.unregisterReceiver(packageChangeReceiver)
        }
    }

    private val legacySourcePackageListener = LegacySourcePackageListener(applicationContext)
    private val serviceConnection = LegacySourceServiceConnection(applicationContext)

    override fun onCreate(owner: LifecycleOwner) {
        serviceLiveData.distinctUntilChanged().observe(owner) { componentName ->
            if (componentName == null) {
                legacySourcePackageListener.startListening()
                serviceConnection.unbindService()
            } else {
                legacySourcePackageListener.stopListening()
                serviceConnection.bindService(componentName)
            }
        }
        MuzeiDatabase.getInstance(applicationContext).providerDao().currentProvider
                .observe(owner, serviceConnection::onProviderChanged)
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

    override fun onDestroy(owner: LifecycleOwner) {
        serviceConnection.unbindService()
    }
}
