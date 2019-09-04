/*
 * Copyright 2014 Google Inc.
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

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.room.withTransaction
import com.google.android.apps.muzei.sources.SourceSubscriberService
import com.google.android.apps.muzei.util.goAsync
import com.google.android.apps.muzei.util.toastFromBackground
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.legacy.BuildConfig
import net.nurik.roman.muzei.legacy.R
import java.util.HashSet
import java.util.concurrent.Executors

/**
 * Class responsible for managing interactions with sources such as subscribing, unsubscribing, and sending actions.
 */
class LegacySourceService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "LegacySourceService"
        private const val ACTION_SUBSCRIBE = "com.google.android.apps.muzei.api.action.SUBSCRIBE"
        private const val EXTRA_SUBSCRIBER_COMPONENT = "com.google.android.apps.muzei.api.extra.SUBSCRIBER_COMPONENT"
        private const val EXTRA_TOKEN = "com.google.android.apps.muzei.api.extra.TOKEN"
        private const val USER_PROPERTY_SELECTED_SOURCE = "selected_source"
        private const val USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package"
        private const val MAX_VALUE_LENGTH = 36

        suspend fun selectSource(
                context: Context,
                source: ComponentName
        ): Source {
            val database = LegacyDatabase.getInstance(context)
            val selectedSource = database.sourceDao().getCurrentSource()
            if (source == selectedSource?.componentName) {
                return selectedSource
            }

            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Source $source selected.")
            }

            return database.withTransaction {
                if (selectedSource != null) {
                    // Unselect the old source
                    selectedSource.selected = false
                    database.sourceDao().update(selectedSource)
                }

                // Select the new source
                val newSource = database.sourceDao().getSourceByComponentName(source)?.apply {
                    selected = true
                    database.sourceDao().update(this)
                } ?: Source(source).apply {
                    selected = true
                    database.sourceDao().insert(this)
                }
                newSource
            }
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)

    private val singleThreadContext by lazy {
        Executors.newSingleThreadExecutor { target ->
            Thread(target, "LegacySourceService")
        }.asCoroutineDispatcher()
    }

    private var replyToMessenger: Messenger? = null

    private val messenger by lazy {
        Messenger(Handler { message ->
            when (message.what) {
                LegacySourceServiceProtocol.WHAT_REGISTER_REPLY_TO -> {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Registered")
                    }
                    replyToMessenger = message.replyTo
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                }
                LegacySourceServiceProtocol.WHAT_NEXT_ARTWORK -> lifecycleScope.launch(singleThreadContext) {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Got next artwork command")
                    }
                    val database = LegacyDatabase.getInstance(applicationContext)
                    val source = database.sourceDao().getCurrentSource()
                    if (source?.supportsNextArtwork == true) {
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Sending next artwork command to ${source.componentName}")
                        }
                        source.sendAction(this@LegacySourceService,
                                LegacySourceServiceProtocol.LEGACY_COMMAND_ID_NEXT_ARTWORK)
                    }
                }
                LegacySourceServiceProtocol.WHAT_ALLOWS_NEXT_ARTWORK -> {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Got allows next artwork")
                    }
                    val replyMessenger = message.replyTo
                    lifecycleScope.launch(singleThreadContext) {
                        val allowsNextArtwork = LegacyDatabase.getInstance(applicationContext)
                                .sourceDao().getCurrentSource()?.supportsNextArtwork == true
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Sending allows next artwork of $allowsNextArtwork")
                        }
                        replyMessenger.send(Message.obtain().apply {
                            arg1 = if (allowsNextArtwork) 1 else 0
                        })
                    }
                }
                LegacySourceServiceProtocol.WHAT_UNREGISTER_REPLY_TO -> {
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Unregistered")
                    }
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                    replyToMessenger = null
                }
            }
            true
        })
    }

    private val sourcePackageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            val packageName = intent.data?.schemeSpecificPart
            // Update the sources from the changed package
            GlobalScope.launch(singleThreadContext) {
                updateSources(packageName)
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                goAsync(lifecycleScope) {
                    val source = LegacyDatabase.getInstance(context)
                            .sourceDao().getCurrentSource()
                    if (source != null && packageName == source.componentName.packageName) {
                        try {
                            context.packageManager.getServiceInfo(source.componentName, 0)
                        } catch (e: PackageManager.NameNotFoundException) {
                            Log.i(TAG, "Selected source ${source.componentName} is no longer available")
                            LegacyDatabase.getInstance(context).sourceDao().delete(source)
                            return@goAsync
                        }

                        // Some other change.
                        Log.i(TAG, "Source package changed or replaced. Re-subscribing to ${source.componentName}")
                        source.subscribe()
                    }
                }
            }
        }
    }

    private suspend fun updateSources(packageName: String? = null) {
        val queryIntent = Intent(LegacySourceServiceProtocol.ACTION_MUZEI_ART_SOURCE)
        if (packageName != null) {
            queryIntent.`package` = packageName
        }
        val pm = packageManager
        val database = LegacyDatabase.getInstance(this)
        database.withTransaction {
            val existingSources = HashSet(if (packageName != null)
                database.sourceDao().getSourcesComponentNamesByPackageName(packageName)
            else
                database.sourceDao().getSourceComponentNames())
            val resolveInfos = pm.queryIntentServices(queryIntent,
                    PackageManager.GET_META_DATA)
            for (ri in resolveInfos) {
                existingSources.remove(ComponentName(ri.serviceInfo.packageName,
                        ri.serviceInfo.name))
                updateSourceFromServiceInfo(ri.serviceInfo)
            }
            // Delete sources in the database that have since been removed
            database.sourceDao().deleteAll(existingSources.toTypedArray())
        }

        // Enable or disable the SourceArtProvider based on whether
        // there are any available sources
        val sources = database.sourceDao().getSources()
        val legacyComponentName = ComponentName(this, SourceArtProvider::class.java)
        val currentState = pm.getComponentEnabledSetting(legacyComponentName)
        val newState = if (sources.isEmpty()) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        if (currentState != newState) {
            pm.setComponentEnabledSetting(legacyComponentName,
                    newState,
                    PackageManager.DONT_KILL_APP)
        }
    }

    private inner class SubscriberLiveData internal constructor() : MediatorLiveData<Source>() {
        private var currentSource: com.google.android.apps.muzei.legacy.Source? = null

        init {
            addSource<com.google.android.apps.muzei.legacy.Source>(LegacyDatabase.getInstance(this@LegacySourceService)
                    .sourceDao().currentSource) { source ->
                if (currentSource != null && source != null &&
                        currentSource?.componentName == source.componentName) {
                    // Don't do anything if it is the same Source
                    return@addSource
                }
                lifecycleScope.launch(Dispatchers.Main) {
                    currentSource?.unsubscribe()
                    currentSource = source
                    if (source != null) {
                        source.subscribe()
                        postValue(source)
                    }
                }
            }
        }

        override fun onInactive() {
            super.onInactive()
            currentSource?.unsubscribe()
        }
    }

    init {
        lifecycle.addObserver(NetworkChangeObserver(this))
    }

    override fun getLifecycle() = lifecycleRegistry

    override fun onBind(intent: Intent): IBinder? {
        return messenger.binder
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        SubscriberLiveData().observe(this) { source ->
            sendSelectedSourceAnalytics(source.componentName)
        }
        var currentSource: Source? = null
        LegacyDatabase.getInstance(this).sourceDao().currentSource.observe(this) { source ->
            if (currentSource != null && source == null) {
                // The selected source has been removed or was otherwise deselected
                replyToMessenger?.send(Message.obtain().apply {
                    what = LegacySourceServiceProtocol.WHAT_REPLY_TO_NO_SELECTED_SOURCE
                })
            }
            currentSource = source
        }
        // Register for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        registerReceiver(sourcePackageChangeReceiver, packageChangeFilter)
        // Update the available sources in case we missed anything while Muzei was disabled
        GlobalScope.launch(singleThreadContext) {
            updateSources()
        }
    }

    private suspend fun updateSourceFromServiceInfo(info: ServiceInfo) {
        val pm = packageManager
        val metaData = info.metaData
        val componentName = ComponentName(info.packageName, info.name)
        val sourceDao = LegacyDatabase.getInstance(this).sourceDao()
        val existingSource = sourceDao.getSourceByComponentName(componentName)
        if (metaData != null && metaData.containsKey("replacement")) {
            // Skip sources having a replacement MuzeiArtProvider that should be used instead
            if (existingSource != null) {
                if (existingSource.selected) {
                    // If this is the selected source, switch Muzei to the new MuzeiArtProvider
                    // rather than continue to use the legacy art source
                    metaData.getString("replacement").takeUnless { it.isNullOrEmpty() }?.run {
                        val providerInfo = pm.resolveContentProvider(this, 0)
                                ?: try {
                                    val replacement = ComponentName(packageName, this)
                                    pm.getProviderInfo(replacement, 0)
                                } catch (e: PackageManager.NameNotFoundException) {
                                    // Invalid
                                    null
                                }
                        if (providerInfo != null) {
                            replyToMessenger?.send(Message.obtain().apply {
                                what = LegacySourceServiceProtocol.WHAT_REPLY_TO_REPLACEMENT
                                obj = providerInfo.authority
                            })
                        }
                    }
                }
                sourceDao.delete(existingSource)
            }
            return
        } else if (!info.isEnabled) {
            // Disabled sources can't be used
            if (existingSource != null) {
                sourceDao.delete(existingSource)
            }
            return
        }
        val source = existingSource ?: Source(componentName)
        source.label = info.loadLabel(pm).toString()
        source.targetSdkVersion = info.applicationInfo.targetSdkVersion
        if (source.targetSdkVersion >= Build.VERSION_CODES.O) {
            // The Legacy API is incompatible with apps
            // targeting Android O+
            source.selected = false
        }
        if (info.descriptionRes != 0) {
            try {
                val packageContext = createPackageContext(
                        source.componentName.packageName, 0)
                val packageRes = packageContext.resources
                source.defaultDescription = packageRes.getString(info.descriptionRes)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Can't read package resources for source ${source.componentName}")
            } catch (e: Resources.NotFoundException) {
                Log.e(TAG, "Can't read package resources for source ${source.componentName}")
            }
        }
        source.color = Color.WHITE
        if (metaData != null) {
            val settingsActivity = metaData.getString("settingsActivity")
            if (!settingsActivity.isNullOrEmpty()) {
                source.settingsActivity = ComponentName.unflattenFromString(
                        "${info.packageName}/$settingsActivity")
            }
            val setupActivity = metaData.getString("setupActivity")
            if (!setupActivity.isNullOrEmpty()) {
                source.setupActivity = ComponentName.unflattenFromString(
                        "${info.packageName}/$setupActivity")
            }
            source.color = metaData.getInt("color", source.color)
            try {
                val hsv = FloatArray(3)
                Color.colorToHSV(source.color, hsv)
                var adjust = false
                if (hsv[2] < 0.8f) {
                    hsv[2] = 0.8f
                    adjust = true
                }
                if (hsv[1] > 0.4f) {
                    hsv[1] = 0.4f
                    adjust = true
                }
                if (adjust) {
                    source.color = Color.HSVToColor(hsv)
                }
                if (Color.alpha(source.color) != 255) {
                    source.color = Color.argb(255,
                            Color.red(source.color),
                            Color.green(source.color),
                            Color.blue(source.color))
                }
            } catch (ignored: IllegalArgumentException) {
            }
        }
        if (existingSource == null) {
            sourceDao.insert(source)
        } else {
            sourceDao.update(source)
        }
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        unregisterReceiver(sourcePackageChangeReceiver)
        super.onDestroy()
    }

    private fun sendSelectedSourceAnalytics(selectedSource: ComponentName) {
        // The current limit for user property values
        var packageName = selectedSource.packageName
        if (packageName.length > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length - MAX_VALUE_LENGTH)
        }
        FirebaseAnalytics.getInstance(this).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName)
        var className = selectedSource.flattenToShortString()
        className = className.substring(className.indexOf('/') + 1)
        if (className.length > MAX_VALUE_LENGTH) {
            className = className.substring(className.length - MAX_VALUE_LENGTH)
        }
        FirebaseAnalytics.getInstance(this).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
                className)
    }

    private suspend fun Source.subscribe() {
        val selectedSource = componentName
        try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Subscribing to $selectedSource")
            }
            // Ensure that we have a valid service before subscribing
            packageManager.getServiceInfo(selectedSource, 0)
            startService(Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            ComponentName(this@LegacySourceService, SourceSubscriberService::class.java))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "Selected source $selectedSource is no longer available; switching to default.", e)
            toastFromBackground(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
            LegacyDatabase.getInstance(this@LegacySourceService).sourceDao().delete(this)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Selected source $selectedSource is no longer available; switching to default.", e)
            toastFromBackground(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
            LegacyDatabase.getInstance(this@LegacySourceService).sourceDao()
                    .update(apply { selected = false })
        } catch (e: SecurityException) {
            Log.i(TAG, "Selected source $selectedSource is no longer available; switching to default.", e)
            toastFromBackground(R.string.legacy_source_unavailable, Toast.LENGTH_LONG)
            LegacyDatabase.getInstance(this@LegacySourceService).sourceDao()
                    .update(apply { selected = false })
        }
    }

    private fun Source.unsubscribe() {
        val selectedSource = componentName
        try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Unsubscribing to $selectedSource")
            }
            // Ensure that we have a valid service before subscribing
            packageManager.getServiceInfo(selectedSource, 0)
            startService(Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            ComponentName(this@LegacySourceService, SourceSubscriberService::class.java))
                    .putExtra(EXTRA_TOKEN, null as String?))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "Unsubscribing to $selectedSource failed.", e)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Unsubscribing to $selectedSource failed.", e)
        } catch (e: SecurityException) {
            Log.i(TAG, "Unsubscribing to $selectedSource failed.", e)
        }
    }
}
