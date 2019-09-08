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

@file:Suppress("DEPRECATION")

package com.google.android.apps.muzei.legacy

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.MutableLiveData
import androidx.navigation.NavDeepLinkBuilder
import com.google.android.apps.muzei.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

class LegacySourcePackageListener(
        private val applicationContext: Context
) {
    companion object {
        private const val TAG = "LegacySourcePackage"
        private const val PREF_LAST_NOTIFIED = "legacy_last_notified"
        private const val NOTIFICATION_CHANNEL = "legacy"
        private const val NOTIFICATION_ID = 19
        private const val NOTIFICATION_SUMMARY_TAG = "summary"
        private const val NOTIFICATION_GROUP_KEY = "legacy"
    }

    internal val unsupportedSources: MutableLiveData<List<LegacySourceInfo>> = MutableLiveData()

    private val largeIconSize = applicationContext.resources.getDimensionPixelSize(
            android.R.dimen.notification_large_icon_height)
    private var lastNotifiedSources = mutableSetOf<LegacySourceInfo>()
    private val prefs = Prefs.getSharedPreferences(applicationContext)

    init {
        prefs.getStringSet(PREF_LAST_NOTIFIED, mutableSetOf())?.forEach { packageName ->
            lastNotifiedSources.add(LegacySourceInfo(packageName))
        }
        if (lastNotifiedSources.isNotEmpty()) {
            GlobalScope.launch(Dispatchers.Main.immediate) {
                unsupportedSources.value = lastNotifiedSources.toList()
            }
        }
    }

    private var registered = false

    private val sourcePackageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            val packageName = intent.data?.schemeSpecificPart
            // Update the sources from the changed package
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Package $packageName changed")
            }
            queryLegacySources()
        }
    }

    fun startListening() {
        if (registered) {
            return
        }
        // Register for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        applicationContext.registerReceiver(sourcePackageChangeReceiver, packageChangeFilter)
        registered = true
        queryLegacySources()
    }

    private fun queryLegacySources() {
        val queryIntent = Intent(LegacySourceServiceProtocol.ACTION_MUZEI_ART_SOURCE)
        val pm = applicationContext.packageManager
        val resolveInfos = pm.queryIntentServices(queryIntent,
                PackageManager.GET_META_DATA)
        val legacySources = mutableSetOf<LegacySourceInfo>()
        for (ri in resolveInfos) {
            val info = ri.serviceInfo
            if (info?.metaData?.containsKey("replacement") == true) {
                // Skip MuzeiArtSources that have a replacement
                continue
            }
            if (BuildConfig.DEBUG) {
                val legacySource = ComponentName(ri.serviceInfo.packageName,
                        ri.serviceInfo.name)
                Log.d(TAG, "Found legacy source $legacySource")
            }
            val sourceInfo = LegacySourceInfo(ri.serviceInfo.packageName).apply {
                title = info.applicationInfo.loadLabel(pm).toString()
                icon = generateSourceImage(info.applicationInfo.loadIcon(pm))
            }
            legacySources.add(sourceInfo)
        }
        unsupportedSources.value = legacySources.toList()
        if (lastNotifiedSources == legacySources) {
            // Nothing changed, so there's nothing to update
            return
        }
        val additions = legacySources - lastNotifiedSources
        val removals = lastNotifiedSources - legacySources
        val notificationManager = applicationContext.getSystemService(
                Context.NOTIFICATION_SERVICE) as NotificationManager
        // Cancel the notification associated with sources that have since been removed
        removals.forEach {
            notificationManager.cancel(it.packageName, NOTIFICATION_ID)
        }
        lastNotifiedSources = legacySources
        prefs.edit {
            putStringSet(PREF_LAST_NOTIFIED, lastNotifiedSources.map { it.packageName }.toSet())
        }
        if (legacySources.isEmpty()) {
            // If there's no Legacy Sources, cancel any summary notification still present
            notificationManager.cancel(NOTIFICATION_SUMMARY_TAG, NOTIFICATION_ID)
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            }
            val contentIntent = NavDeepLinkBuilder(applicationContext)
                    .setGraph(R.navigation.main_navigation)
                    .setDestination(R.id.legacy_source_info)
                    .createPendingIntent()
            val learnMorePendingIntent = PendingIntent.getActivity(
                    applicationContext, 0,
                    Intent(Intent.ACTION_VIEW, LegacySourceManager.LEARN_MORE_LINK).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    0)
            // Send a notification for each new Legacy Source
            for (info in additions) {
                val sendFeedbackPendingIntent = PendingIntent.getActivity(
                        applicationContext, 0,
                        Intent(Intent.ACTION_VIEW,
                                "https://play.google.com/store/apps/details?id=${info.packageName}".toUri()),
                        0)
                val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_muzei)
                        .setColor(ContextCompat.getColor(applicationContext, R.color.notification))
                        .setContentTitle(applicationContext.getString(
                                R.string.legacy_notification_title, info.title))
                        .setContentText(applicationContext.getString(R.string.legacy_notification_text))
                        .setContentIntent(contentIntent)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(
                                applicationContext.getString(R.string.legacy_notification_text)))
                        .setLargeIcon(info.icon)
                        .setGroup(NOTIFICATION_GROUP_KEY)
                        .setLocalOnly(true)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .setOnlyAlertOnce(true)
                        .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notif_info,
                                applicationContext.getString(R.string.legacy_action_learn_more),
                                learnMorePendingIntent).build())
                        .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notif_feedback,
                                applicationContext.getString(R.string.legacy_action_send_feedback),
                                sendFeedbackPendingIntent).build())
                        .build()
                notificationManager.notify(info.packageName, NOTIFICATION_ID, notification)
            }
            // Send a summary notification
            val summaryText = applicationContext.resources.getQuantityString(
                    R.plurals.legacy_summary_text,
                    legacySources.size, legacySources.size)
            val summaryNotification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(applicationContext, R.color.notification))
                    .setContentTitle(applicationContext.getString(R.string.legacy_summary_title))
                    .setContentText(summaryText)
                    .setContentIntent(contentIntent)
                    .setShowWhen(false)
                    .setStyle(NotificationCompat.InboxStyle()
                            .setSummaryText(summaryText)
                            .also {
                                for (info in legacySources) {
                                    it.addLine(info.title)
                                }
                            })
                    .setGroup(NOTIFICATION_GROUP_KEY)
                    .setGroupSummary(true)
                    .setLocalOnly(true)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setOnlyAlertOnce(true)
                    .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notif_info,
                            applicationContext.getString(R.string.legacy_action_learn_more),
                            learnMorePendingIntent).build())
                    .build()
            notificationManager.notify(NOTIFICATION_SUMMARY_TAG, NOTIFICATION_ID, summaryNotification)
        }
    }

    private fun generateSourceImage(image: Drawable?) = image?.run {
        Bitmap.createBitmap(largeIconSize, largeIconSize,
            Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            image.setBounds(0, 0, largeIconSize, largeIconSize)
            image.draw(canvas)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(applicationContext)
        val channel = NotificationChannel(NOTIFICATION_CHANNEL,
                applicationContext.getString(R.string.legacy_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)
    }

    fun stopListening() {
        if (!registered) {
            return
        }
        registered = false
        applicationContext.unregisterReceiver(sourcePackageChangeReceiver)
    }
}

data class LegacySourceInfo(val packageName: String) {
    lateinit var title: String
    var icon: Bitmap? = null
}
