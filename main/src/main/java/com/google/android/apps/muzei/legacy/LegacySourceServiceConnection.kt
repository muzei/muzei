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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sync.ProviderManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.LEGACY_AUTHORITY
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Class responsible for managing interactions with legacy sources.
 */
internal class LegacySourceServiceConnection(
        private val applicationContext: Context
) : ServiceConnection {

    companion object {
        private const val TAG = "LegacySourceServiceCon"
    }

    private var currentProvider: Provider? = null

    private val replyToMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                LegacySourceServiceProtocol.WHAT_REPLY_TO_REPLACEMENT -> {
                    val authority = message.obj as String
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Got replacement message for $authority")
                    }
                    GlobalScope.launch {
                        ProviderManager.select(applicationContext, authority)
                    }
                }
                LegacySourceServiceProtocol.WHAT_REPLY_TO_NO_SELECTED_SOURCE -> GlobalScope.launch {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Got no selected source message")
                    }
                    ProviderManager.select(applicationContext, FEATURED_ART_AUTHORITY)
                }
            }
            true
        })
    }

    private var messenger: Messenger? = null
    private var handlerThread: HandlerThread? = null
    private var registered = false

    fun onProviderChanged(provider: Provider?) {
        if (provider?.authority == LEGACY_AUTHORITY) {
            register()
        } else {
            unregister()
        }
    }

    fun bindService(componentName: ComponentName) {
        val binding = applicationContext.bindService(
                Intent().apply { component = componentName },
                this,
                Context.BIND_AUTO_CREATE)
        if (BuildConfig.DEBUG) {
            if (binding) {
                Log.d(TAG, "Binding to LegacySourceService")
            } else {
                Log.d(TAG, "Could not bind to LegacySourceService")
            }
        }
    }

    override fun onServiceConnected(name: ComponentName, service: IBinder) {
        messenger = Messenger(service)
        handlerThread = HandlerThread(TAG).apply { start() }
        if (currentProvider?.authority == LEGACY_AUTHORITY) {
            // Register immediately if the legacy art provider is selected
            register()
        }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Bound to LegacySourceService")
        }
    }

    private fun register() {
        // Only register if we're connected
        val messenger = this.messenger ?: return
        if (!registered) {
            registered = true
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Sending register message")
            }
            messenger.send(Message.obtain().apply {
                what = LegacySourceServiceProtocol.WHAT_REGISTER_REPLY_TO
                replyTo = replyToMessenger
            })
        }
    }

    fun nextArtwork() {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Sending Next Artwork message")
        }
        messenger?.send(Message.obtain().apply {
            what = LegacySourceServiceProtocol.WHAT_NEXT_ARTWORK
        })
    }

    suspend fun allowsNextArtwork(): Boolean = suspendCoroutine { cont ->
        when(val messenger = this.messenger) {
            null -> cont.resume(false)
            else -> {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Sending allows next artwork message")
                }
                messenger.send(Message.obtain().apply {
                    what = LegacySourceServiceProtocol.WHAT_ALLOWS_NEXT_ARTWORK
                    replyTo = Messenger(Handler(handlerThread!!.looper) { message ->
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Answered allows next artwork ${message.arg1 == 1}")
                        }
                        cont.resume(message.arg1 == 1)
                        true
                    })
                })
            }
        }
    }

    private fun unregister() {
        // Only unregister if we're connected
        val messenger = this.messenger ?: return
        if (registered) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Sending unregister message")
            }
            messenger.send(Message.obtain().apply {
                what = LegacySourceServiceProtocol.WHAT_UNREGISTER_REPLY_TO
            })
            registered = false
        }
    }

    override fun onServiceDisconnected(name: ComponentName) {
        messenger = null
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Disconnected from LegacySourceService")
        }
    }

    fun unbindService() {
        if (messenger != null) {
            unregister()
            applicationContext.unbindService(this)
            handlerThread?.quitSafely()
            messenger = null
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Unbound from LegacySourceService")
            }
        }
    }
}
