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
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.distinctUntilChanged
import androidx.lifecycle.observe
import com.google.android.apps.muzei.featuredart.BuildConfig.FEATURED_ART_AUTHORITY
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Provider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.filterNotNull
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.LEGACY_AUTHORITY
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
        private const val TAG = "LegacySourceManager"

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
    private val currentProviderLiveData = MuzeiDatabase.getInstance(applicationContext)
            .providerDao().currentProvider

    private val replyToMessenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            when (message.what) {
                LegacySourceServiceProtocol.WHAT_REPLY_TO_REPLACEMENT -> {
                    val authority = message.obj as String
                    GlobalScope.launch {
                        ProviderManager.select(applicationContext, authority)
                    }
                }
                LegacySourceServiceProtocol.WHAT_REPLY_TO_NO_SELECTED_SOURCE -> GlobalScope.launch {
                    ProviderManager.select(applicationContext, FEATURED_ART_AUTHORITY)
                }
            }
            true
        })
    }

    private var messenger: Messenger? = null
    private var registered = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            messenger = Messenger(service).also {
                if (currentProviderLiveData.value?.authority == LEGACY_AUTHORITY) {
                    // Register immediately if the legacy art provider is selected
                    register()
                }
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
        serviceLiveData.distinctUntilChanged().filterNotNull().observe(owner) { componentName ->
            bindService(componentName)
        }
        MuzeiDatabase.getInstance(applicationContext).providerDao().currentProvider.observe(owner) { provider ->
            if (provider?.authority == LEGACY_AUTHORITY) {
                register()
            } else {
                unregister()
            }
        }
    }

    private fun bindService(componentName: ComponentName) {
        val binding = applicationContext.bindService(
                Intent().apply { component = componentName },
                serviceConnection,
                Context.BIND_AUTO_CREATE)
        if (BuildConfig.DEBUG) {
            if (binding) {
                Log.d(TAG, "Binding to LegacySourceService")
            } else {
                Log.d(TAG, "Could not bind to LegacySourceService")
            }
        }
    }

    private fun register() {
        // Only register if we're connected
        val messenger = this.messenger ?: return
        if (!registered) {
            registered = true
            messenger.send(Message.obtain().apply {
                what = LegacySourceServiceProtocol.WHAT_REGISTER_REPLY_TO
                replyTo = replyToMessenger
            })
        }
    }

    suspend fun nextArtwork() {
        val provider = MuzeiDatabase.getInstance(applicationContext)
                .providerDao().getCurrentProvider()
        if (provider?.authority == LEGACY_AUTHORITY) {
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

    private fun unregister() {
        // Only unregister if we're connected
        val messenger = this.messenger ?: return
        if (registered) {
            messenger.send(Message.obtain().apply {
                what = LegacySourceServiceProtocol.WHAT_UNREGISTER_REPLY_TO
            })
            registered = false
        }
    }

    private fun unbindService() {
        if (messenger != null) {
            unregister()
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
