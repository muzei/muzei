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
import androidx.core.net.toUri
import com.google.android.apps.muzei.api.MuzeiArtSource
import net.nurik.roman.muzei.R

class LegacySourcePackageListener(
        private val applicationContext: Context
) {
    companion object {
        private const val TAG = "LegacySourcePackage"
        private const val NOTIFICATION_CHANNEL = "legacy"
        private const val NOTIFICATION_ID = 19
        private val LEARN_MORE_LINK =
                "https://medium.com/muzei/muzei-3-0-and-legacy-sources-8261979e2264".toUri()
    }

    private val largeIconSize = applicationContext.resources.getDimensionPixelSize(
            android.R.dimen.notification_large_icon_height)
    private var lastNotifiedSources = mutableSetOf<SourceInfo>()

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
        val queryIntent = Intent(MuzeiArtSource.ACTION_MUZEI_ART_SOURCE)
        val pm = applicationContext.packageManager
        val resolveInfos = pm.queryIntentServices(queryIntent,
                PackageManager.GET_META_DATA)
        val legacySources = mutableSetOf<SourceInfo>()
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
            val sourceInfo = SourceInfo(ri.serviceInfo.packageName).apply {
                title = info.applicationInfo.loadLabel(pm).toString()
                icon = generateSourceImage(info.applicationInfo.loadIcon(pm))
            }
            legacySources.add(sourceInfo)
        }
        if (lastNotifiedSources == legacySources) {
            // Nothing changed, so there's nothing to update
            return
        }
        lastNotifiedSources = legacySources
        if (legacySources.isNotEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel()
            }
            val learnMorePendingIntent = PendingIntent.getActivity(
                    applicationContext, 0,
                    Intent(Intent.ACTION_VIEW, LEARN_MORE_LINK).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    },
                    0)
            val notificationManager = NotificationManagerCompat.from(applicationContext)
            for (info in legacySources) {
                val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL)
                        .setSmallIcon(R.drawable.ic_stat_muzei)
                        .setColor(ContextCompat.getColor(applicationContext, R.color.notification))
                        .setContentTitle(applicationContext.getString(
                                R.string.legacy_notification_title, info.title))
                        .setContentText(applicationContext.getString(R.string.legacy_notification_text))
                        .setStyle(NotificationCompat.BigTextStyle().bigText(
                                applicationContext.getString(R.string.legacy_notification_text)))
                        .setLargeIcon(info.icon)
                        .setLocalOnly(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setDefaults(NotificationCompat.DEFAULT_ALL)
                        .setOnlyAlertOnce(true)
                        .addAction(NotificationCompat.Action.Builder(R.drawable.ic_notif_info,
                                applicationContext.getString(R.string.legacy_action_learn_more),
                                learnMorePendingIntent).build())
                        .build()
                notificationManager.notify(info.packageName, NOTIFICATION_ID, notification)
            }
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

data class SourceInfo(val packageName: String) {
    lateinit var title: String
    var icon: Bitmap? = null
}
