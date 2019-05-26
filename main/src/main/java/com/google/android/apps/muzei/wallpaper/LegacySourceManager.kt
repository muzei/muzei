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

package com.google.android.apps.muzei.wallpaper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sources.LegacySourceService
import com.google.android.apps.muzei.sources.LegacySourceServiceProtocol
import com.google.android.apps.muzei.sync.ProviderManager
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.SOURCES_AUTHORITY

suspend fun Provider?.allowsNextArtwork(context: Context): Boolean {
    return when {
        this == null -> false
        supportsNextArtwork -> true
        authority != SOURCES_AUTHORITY -> false
        else -> MuzeiDatabase.getInstance(context).sourceDao()
                .getCurrentSource()?.supportsNextArtwork == true
    }
}

/**
 * Class responsible for managing interactions with legacy sources.
 */
class LegacySourceManager(private val applicationContext: Context) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "LegacySourceManager"

        @Volatile
        private var instance: LegacySourceManager? = null

        fun getInstance(context: Context): LegacySourceManager {
            val applicationContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: LegacySourceManager(applicationContext).also { manager ->
                    instance = manager
                }
            }
        }
    }

    private var messenger: Messenger? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Bound to LegacySourceService")
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            messenger = null
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Disconnected from LegacySourceService")
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        MuzeiDatabase.getInstance(applicationContext).providerDao().currentProvider.observe(owner) { provider ->
            if (provider?.authority == SOURCES_AUTHORITY) {
                bindService()
            } else {
                unbindService()
            }
        }
    }

    private fun bindService() {
        if (messenger == null) {
            val binding = applicationContext.bindService(
                    Intent(applicationContext, LegacySourceService::class.java),
                    serviceConnection, Context.BIND_AUTO_CREATE)
            if (BuildConfig.DEBUG) {
                if (binding) {
                    Log.d(TAG, "Binding to LegacySourceService")
                } else {
                    Log.d(TAG, "Could not bind to LegacySourceService")
                }
            }
        }
    }

    suspend fun nextArtwork() {
        val provider = MuzeiDatabase.getInstance(applicationContext)
                .providerDao().getCurrentProvider()
        if (provider?.authority == SOURCES_AUTHORITY) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Sending Next Artwork message")
            }
            messenger?.send(Message.obtain().apply {
                what = LegacySourceServiceProtocol.WHAT_NEXT_ARTWORK
            })
        } else {
            ProviderManager.getInstance(applicationContext).nextArtwork()
        }
    }

    private fun unbindService() {
        if (messenger != null) {
            applicationContext.unbindService(serviceConnection)
            messenger = null
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Unbound from LegacySourceService")
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        unbindService()
    }
}
