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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.SOURCES_AUTHORITY
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

suspend fun Provider?.allowsNextArtwork(context: Context): Boolean {
    return when {
        this == null -> false
        supportsNextArtwork -> true
        authority != SOURCES_AUTHORITY -> false
        else -> LegacySourceManager.getInstance(context).allowsNextArtwork()
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

    private val replyToMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                LegacySourceServiceProtocol.WHAT_REPLY_TO_REPLACEMENT -> {
                    val authority = message.obj as String
                    GlobalScope.launch {
                        ProviderManager.select(applicationContext, authority)
                    }
                }
            }
            true
        })
    }

    private var messenger: Messenger? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service).also {
                it.send(Message.obtain().apply {
                    what = LegacySourceServiceProtocol.WHAT_REGISTER_REPLY_TO
                    replyTo = replyToMessenger
                })
            }
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

    suspend fun allowsNextArtwork(): Boolean = suspendCoroutine { cont ->
        when(val messenger = this.messenger) {
            null -> cont.resume(false)
            else -> {
                messenger.send(Message.obtain().apply {
                    what = LegacySourceServiceProtocol.WHAT_ALLOWS_NEXT_ARTWORK
                    replyTo = Messenger(Handler(Looper.getMainLooper()) { message ->
                        cont.resume(message.arg1 == 1)
                        true
                    })
                })
            }
        }
    }

    private fun unbindService() {
        if (messenger != null) {
            messenger?.send(Message.obtain().apply {
                what = LegacySourceServiceProtocol.WHAT_UNREGISTER_REPLY_TO
            })
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
