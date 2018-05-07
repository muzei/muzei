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

package com.google.android.apps.muzei.sources

import android.annotation.SuppressLint
import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LifecycleRegistry
import android.arch.lifecycle.MediatorLiveData
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.graphics.Color
import android.os.AsyncTask
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.core.widget.toast
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND
import com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT
import com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN
import com.google.android.apps.muzei.featuredart.FeaturedArtSource
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Source
import com.google.android.apps.muzei.sync.TaskQueueService
import com.google.android.apps.muzei.util.observeNonNull
import com.google.android.apps.muzei.util.observeOnce
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import java.util.HashSet
import java.util.concurrent.Executors
import kotlin.reflect.KClass

/**
 * Class responsible for managing interactions with sources such as subscribing, unsubscribing, and sending actions.
 */
class SourceManager(private val context: Context) : DefaultLifecycleObserver, LifecycleOwner {

    companion object {
        private const val TAG = "SourceManager"
        private const val USER_PROPERTY_SELECTED_SOURCE = "selected_source"
        private const val USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package"
        private const val MAX_VALUE_LENGTH = 36

        internal fun selectSource(
                context: Context,
                source: KClass<out MuzeiArtSource>,
                callback: (Source) -> Unit = {}
        ) {
            selectSource(context, ComponentName(context, source.java), callback)
        }

        @SuppressLint("StaticFieldLeak")
        fun selectSource(
                context: Context,
                source: ComponentName,
                callback: (Source) -> Unit = {}
        ) {
            object : AsyncTask<Void, Void, Source>() {
                override fun doInBackground(vararg voids: Void): Source {
                    val database = MuzeiDatabase.getInstance(context)
                    val selectedSource = database.sourceDao().currentSourceBlocking
                    if (source == selectedSource?.componentName) {
                        return selectedSource
                    }

                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Source $source selected.")
                    }

                    database.beginTransaction()
                    if (selectedSource != null) {
                        // Unselect the old source
                        selectedSource.selected = false
                        database.sourceDao().update(selectedSource)
                    }

                    // Select the new source
                    val newSource = database.sourceDao().getSourceByComponentNameBlocking(source)?.apply {
                        selected = true
                        database.sourceDao().update(this)
                    } ?: Source(source).apply {
                        selected = true
                        database.sourceDao().insert(this)
                    }

                    database.setTransactionSuccessful()
                    database.endTransaction()

                    return newSource
                }

                override fun onPostExecute(newSource: Source) {
                    callback(newSource)
                }
            }.execute()
        }

        fun sendAction(context: Context, id: Int) {
            MuzeiDatabase.getInstance(context).sourceDao().currentSource.observeOnce { source ->
                if (source != null) {
                    val selectedSource = source.componentName
                    try {
                        // Ensure that we have a valid service before sending the action
                        context.packageManager.getServiceInfo(selectedSource, 0)
                        context.startService(Intent(ACTION_HANDLE_COMMAND)
                                .setComponent(selectedSource)
                                .putExtra(EXTRA_COMMAND_ID, id))
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.i(TAG, "Sending action + $id to $selectedSource failed; switching to default.", e)
                        context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
                        selectSource(context, FeaturedArtSource::class)
                    } catch (e: IllegalStateException) {
                        Log.i(TAG, "Sending action + $id to $selectedSource failed; switching to default.", e)
                        context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
                        selectSource(context, FeaturedArtSource::class)
                    } catch (e: SecurityException) {
                        Log.i(TAG, "Sending action + $id to $selectedSource failed; switching to default.", e)
                        context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
                        selectSource(context, FeaturedArtSource::class)
                    }
                }
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

    private val sourcePackageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            val packageName = intent.data?.schemeSpecificPart
            // Update the sources from the changed package
            executor.execute(UpdateSourcesRunnable(packageName))
            val pendingResult = goAsync()
            MuzeiDatabase.getInstance(context).sourceDao().currentSource.observeOnce { source ->
                if (source != null && packageName == source.componentName.packageName) {
                    try {
                        this@SourceManager.context.packageManager.getServiceInfo(source.componentName, 0)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.i(TAG, "Selected source ${source.componentName} is no longer available")
                        selectSource(context, FeaturedArtSource::class)
                        return@observeOnce
                    }

                    // Some other change.
                    if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        Log.i(TAG, "Source package changed or replaced. Re-subscribing to ${source.componentName}")
                        source.subscribe()
                    }
                }
                pendingResult.finish()
            }
        }
    }
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    private inner class UpdateSourcesRunnable internal constructor(private val packageName: String? = null)
        : Runnable {

        override fun run() {
            val queryIntent = Intent(MuzeiArtSource.ACTION_MUZEI_ART_SOURCE)
            if (packageName != null) {
                queryIntent.`package` = packageName
            }
            val pm = context.packageManager
            val database = MuzeiDatabase.getInstance(context)
            database.beginTransaction()
            val existingSources = HashSet(if (packageName != null)
                database.sourceDao().getSourcesComponentNamesByPackageNameBlocking(packageName)
            else
                database.sourceDao().sourceComponentNamesBlocking)
            val resolveInfos = pm.queryIntentServices(queryIntent,
                    PackageManager.GET_META_DATA)
            if (resolveInfos != null) {
                for (ri in resolveInfos) {
                    existingSources.remove(ComponentName(ri.serviceInfo.packageName,
                            ri.serviceInfo.name))
                    updateSourceFromServiceInfo(ri.serviceInfo)
                }
            }
            // Delete sources in the database that have since been removed
            database.sourceDao().deleteAll(existingSources.toTypedArray())
            database.setTransactionSuccessful()
            database.endTransaction()
        }
    }

    private inner class SubscriberLiveData internal constructor() : MediatorLiveData<Source>() {
        private var currentSource: com.google.android.apps.muzei.room.Source? = null

        init {
            addSource<com.google.android.apps.muzei.room.Source>(MuzeiDatabase.getInstance(context).sourceDao().currentSource) { source ->
                if (currentSource != null && source != null &&
                        currentSource?.componentName == source.componentName) {
                    // Don't do anything if it is the same Source
                    return@addSource
                }
                currentSource?.unsubscribe()
                currentSource = source
                if (source != null) {
                    source.subscribe()
                    value = source
                } else {
                    // Can't have no source at all, so select the default
                    selectSource(context, FeaturedArtSource::class)
                }
            }
        }

        override fun onInactive() {
            super.onInactive()
            currentSource?.unsubscribe()
        }
    }

    init {
        lifecycleRegistry.addObserver(NetworkChangeObserver(context))
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }

    override fun onCreate(owner: LifecycleOwner) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        SubscriberLiveData().observeNonNull(this) { source ->
            sendSelectedSourceAnalytics(source.componentName)
            // Ensure the artwork from the newly selected source is downloaded
            context.startService(TaskQueueService.getDownloadCurrentArtworkIntent(context))
        }
        // Register for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        context.registerReceiver(sourcePackageChangeReceiver, packageChangeFilter)
        // Update the available sources in case we missed anything while Muzei was disabled
        executor.execute(UpdateSourcesRunnable())
    }

    private fun updateSourceFromServiceInfo(info: ServiceInfo) {
        val pm = context.packageManager
        val metaData = info.metaData
        val componentName = ComponentName(info.packageName, info.name)
        val sourceDao = MuzeiDatabase.getInstance(context).sourceDao()
        val existingSource = sourceDao.getSourceByComponentNameBlocking(componentName)
        if (!info.isEnabled) {
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
            // The MuzeiArtSource API is incompatible with apps
            // targeting Android O+
            source.selected = false
        }
        if (info.descriptionRes != 0) {
            try {
                val packageContext = context.createPackageContext(
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
            if (!TextUtils.isEmpty(settingsActivity)) {
                source.settingsActivity = ComponentName.unflattenFromString(
                        "${info.packageName}/$settingsActivity")
            }
            val setupActivity = metaData.getString("setupActivity")
            if (!TextUtils.isEmpty(setupActivity)) {
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

    override fun onDestroy(owner: LifecycleOwner) {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        context.unregisterReceiver(sourcePackageChangeReceiver)
    }

    private fun sendSelectedSourceAnalytics(selectedSource: ComponentName) {
        // The current limit for user property values
        var packageName = selectedSource.packageName
        if (packageName.length > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length - MAX_VALUE_LENGTH)
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName)
        var className = selectedSource.flattenToShortString()
        className = className.substring(className.indexOf('/') + 1)
        if (className.length > MAX_VALUE_LENGTH) {
            className = className.substring(className.length - MAX_VALUE_LENGTH)
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
                className)
    }

    private fun Source.subscribe() {
        val selectedSource = componentName
        try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Subscribing to $selectedSource")
            }
            // Ensure that we have a valid service before subscribing
            context.packageManager.getServiceInfo(selectedSource, 0)
            context.startService(Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            ComponentName(context, SourceSubscriberService::class.java))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()))
        } catch (e: PackageManager.NameNotFoundException) {
            Log.i(TAG, "Selected source $selectedSource is no longer available; switching to default.", e)
            context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
            selectSource(context, FeaturedArtSource::class)
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Selected source $selectedSource is no longer available; switching to default.", e)
            context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
            selectSource(context, FeaturedArtSource::class)
        } catch (e: SecurityException) {
            Log.i(TAG, "Selected source $selectedSource is no longer available; switching to default.", e)
            context.toast(R.string.source_unavailable, Toast.LENGTH_LONG)
            selectSource(context, FeaturedArtSource::class)
        }
    }

    private fun Source.unsubscribe() {
        val selectedSource = componentName
        try {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Unsubscribing to $selectedSource")
            }
            // Ensure that we have a valid service before subscribing
            context.packageManager.getServiceInfo(selectedSource, 0)
            context.startService(Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            ComponentName(context, SourceSubscriberService::class.java))
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
