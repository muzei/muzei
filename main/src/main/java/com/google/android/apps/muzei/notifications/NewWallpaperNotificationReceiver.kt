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

package com.google.android.apps.muzei.notifications

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.ArtDetailOpen
import com.google.android.apps.muzei.ArtworkInfoRedirectActivity
import com.google.android.apps.muzei.legacy.LegacySourceManager
import com.google.android.apps.muzei.legacy.allowsNextArtwork
import com.google.android.apps.muzei.render.ContentUriImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.util.goAsync
import com.google.android.apps.muzei.util.sendFromBackground
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.androidclientcommon.R as CommonR

class NewWallpaperNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val PREF_ENABLED = "new_wallpaper_notification_enabled"
        private const val PREF_LAST_READ_NOTIFICATION_ARTWORK_ID = "last_read_notification_artwork_id"

        private const val NOTIFICATION_CHANNEL = "new_wallpaper"
        private const val NOTIFICATION_ID = 1234

        private const val ACTION_MARK_NOTIFICATION_READ = "com.google.android.apps.muzei.action.NOTIFICATION_DELETED"

        private const val ACTION_NEXT_ARTWORK = "com.google.android.apps.muzei.action.NOTIFICATION_NEXT_ARTWORK"

        private const val ACTION_USER_COMMAND = "com.google.android.apps.muzei.action.NOTIFICATION_USER_COMMAND"

        private const val EXTRA_USER_COMMAND = "com.google.android.apps.muzei.extra.USER_COMMAND"

        suspend fun markNotificationRead(context: Context) {
            val lastArtwork = MuzeiDatabase.getInstance(context).artworkDao()
                    .getCurrentArtwork()
            if (lastArtwork != null) {
                val sp = PreferenceManager.getDefaultSharedPreferences(context)
                sp.edit {
                    putLong(PREF_LAST_READ_NOTIFICATION_ARTWORK_ID, lastArtwork.id)
                }
            }

            cancelNotification(context)
        }

        fun cancelNotification(context: Context) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        private fun isNewWallpaperNotificationEnabled(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // On O+ devices, we defer to the system setting
                if (!createNotificationChannel(context)) {
                    // Don't post the new wallpaper notification in the case where
                    // we've also posted the 'Review your settings' notification
                    return false
                }
                val notificationManager = NotificationManagerCompat.from(context)
                val channel = notificationManager
                        .getNotificationChannelCompat(NOTIFICATION_CHANNEL)
                return channel != null && channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
            }
            // Prior to O, we maintain our own preference
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            return sp.getBoolean(PREF_ENABLED, true)
        }

        @SuppressLint("InlinedApi")
        suspend fun maybeShowNewArtworkNotification(context: Context) {
            if (ArtDetailOpen.value) {
                return
            }

            if (!isNewWallpaperNotificationEnabled(context)) {
                return
            }

            val contentResolver = context.contentResolver
            val provider = MuzeiDatabase.getInstance(context)
                    .providerDao()
                    .getCurrentProvider()
            val artwork = MuzeiDatabase.getInstance(context)
                    .artworkDao()
                    .getCurrentArtwork()
            if (provider == null || artwork == null) {
                return
            }

            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            val currentArtworkId = artwork.id
            val lastReadArtworkId = sp.getLong(PREF_LAST_READ_NOTIFICATION_ARTWORK_ID, -1)
            // We've already dismissed the notification if the IDs match
            if (lastReadArtworkId == currentArtworkId) {
                return
            }
            val largeIconHeight = context.resources
                    .getDimensionPixelSize(android.R.dimen.notification_large_icon_height)
            val imageLoader = ContentUriImageLoader(contentResolver, artwork.contentUri)
            val largeIcon = withContext(Dispatchers.IO) { imageLoader.decode(largeIconHeight) } ?: return
            val bigPicture = withContext(Dispatchers.IO) { imageLoader.decode(400) } ?: return

            createNotificationChannel(context)

            try {
                ContextCompat.getDrawable(context, CommonR.drawable.ic_stat_muzei)
            } catch (e : Resources.NotFoundException) {
                Log.e("Notification", "Invalid installation: " +
                        "missing notification icon", e)
                return
            }
            val launchIntent = withContext(Dispatchers.IO) {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
            }
            val artworkTitle = artwork.title
            val title = artworkTitle?.takeUnless { it.isEmpty() }
                    ?: context.getString(CommonR.string.app_name)
            val nb = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                    .setSmallIcon(CommonR.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, CommonR.color.notification))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setAutoCancel(true)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.notification_new_wallpaper))
                    .setLargeIcon(largeIcon)
                    .setContentIntent(PendingIntent.getActivity(context, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                            Intent(context, NewWallpaperNotificationReceiver::class.java)
                                    .setAction(ACTION_MARK_NOTIFICATION_READ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val style = NotificationCompat.BigPictureStyle()
                    .bigLargeIcon(null as Bitmap?)
                    .setBigContentTitle(title)
                    .setSummaryText(artwork.byline)
                    .bigPicture(bigPicture)
            nb.setStyle(style)

            val extender = NotificationCompat.WearableExtender()

            // Support Next Artwork
            if (provider.allowsNextArtwork(context)) {
                val nextPendingIntent = PendingIntent.getBroadcast(context, 0,
                        Intent(context, NewWallpaperNotificationReceiver::class.java)
                                .setAction(ACTION_NEXT_ARTWORK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val nextAction = NotificationCompat.Action.Builder(
                        R.drawable.ic_notif_next_artwork,
                        context.getString(R.string.action_next_artwork_condensed),
                        nextPendingIntent)
                        .extend(NotificationCompat.Action.WearableExtender().setAvailableOffline(false)
                                .setHintDisplayActionInline(true))
                        .build()
                nb.addAction(nextAction)
                extender.addAction(nextAction)
            }
            val commands = artwork.getCommands(context)
            // Show custom actions as a selectable list on Android Wear devices
            if (commands.isNotEmpty()) {
                val actions = commands.map { it.title }.toTypedArray()
                val userCommandPendingIntent = PendingIntent.getBroadcast(context, 0,
                        Intent(context, NewWallpaperNotificationReceiver::class.java)
                                .setAction(ACTION_USER_COMMAND),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                val remoteInput = RemoteInput.Builder(EXTRA_USER_COMMAND)
                        .setAllowFreeFormInput(false)
                        .setLabel(context.getString(R.string.action_user_command_prompt))
                        .setChoices(actions)
                        .build()
                extender.addAction(NotificationCompat.Action.Builder(
                        R.drawable.ic_notif_user_command,
                        context.getString(R.string.action_user_command),
                        userCommandPendingIntent).addRemoteInput(remoteInput)
                        .extend(NotificationCompat.Action.WearableExtender().setAvailableOffline(false))
                        .build())
            }
            val viewPendingIntent = PendingIntent.getActivity(context, 0,
                    ArtworkInfoRedirectActivity.getIntent(context, "notification"),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val viewAction = NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_info,
                    context.getString(R.string.action_artwork_info),
                    viewPendingIntent)
                    .extend(NotificationCompat.Action.WearableExtender().setAvailableOffline(false))
                    .build()
            nb.addAction(viewAction)
            extender.addAction(viewAction)
            nb.extend(extender)

            // Hide the image and artwork title for the public version
            val publicBuilder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                    .setSmallIcon(CommonR.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, CommonR.color.notification))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(CommonR.string.app_name))
                    .setContentText(context.getString(R.string.notification_new_wallpaper))
                    .setContentIntent(PendingIntent.getActivity(context, 0, launchIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                            Intent(context, NewWallpaperNotificationReceiver::class.java)
                                    .setAction(ACTION_MARK_NOTIFICATION_READ),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            nb.setPublicVersion(publicBuilder.build())

            val nm = NotificationManagerCompat.from(context)
            nm.notify(NOTIFICATION_ID, nb.build())

            // Reset the last read notification
            sp.edit {
                remove(PREF_LAST_READ_NOTIFICATION_ARTWORK_ID)
            }
        }

        /**
         * Create the notification channel for the New Wallpaper notification
         * @return False only in the case where the user had wallpapers disabled in-app, but has not
         * yet seen the 'Review your notification settings' notification
         */
        internal fun createNotificationChannel(context: Context): Boolean {
            val notificationManager = NotificationManagerCompat.from(context)
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            // On O+ devices, we want to push users to change the system notification setting
            // but we'll use their current value to set the default importance
            val defaultImportance = if (sp.getBoolean(PREF_ENABLED, true))
                NotificationManagerCompat.IMPORTANCE_MIN
            else
                NotificationManagerCompat.IMPORTANCE_NONE
            if (sp.contains(PREF_ENABLED) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sp.edit { remove(PREF_ENABLED) }
                if (defaultImportance == NotificationManagerCompat.IMPORTANCE_NONE) {
                    // Check to see if there was already a channel and give users an
                    // easy way to review their notification settings if they had
                    // previously disabled notifications but have not yet disabled
                    // the channel
                    val existingChannel = notificationManager
                            .getNotificationChannelCompat(NOTIFICATION_CHANNEL)
                    if (existingChannel != null && existingChannel.importance != NotificationManagerCompat.IMPORTANCE_NONE) {
                        // Construct an Intent to get to the notification settings screen
                        val settingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        settingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        settingsIntent.putExtra(Settings.EXTRA_CHANNEL_ID, NOTIFICATION_CHANNEL)
                        // Build the notification
                        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                                .setSmallIcon(CommonR.drawable.ic_stat_muzei)
                                .setColor(ContextCompat.getColor(context, CommonR.color.notification))
                                .setAutoCancel(true)
                                .setContentTitle(context.getText(R.string.notification_settings_moved_title))
                                .setContentText(context.getText(R.string.notification_settings_moved_text))
                                .setContentIntent(PendingIntent.getActivity(context, 0,
                                        settingsIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                                .setStyle(NotificationCompat.BigTextStyle()
                                        .bigText(context.getText(R.string.notification_settings_moved_text)))
                        notificationManager.notify(1, builder.build())
                        return false
                    }
                }
            }
            val channel = NotificationChannelCompat.Builder(NOTIFICATION_CHANNEL,
                    defaultImportance)
                    .setName(context.getString(R.string.notification_new_wallpaper_channel_name))
                    .setShowBadge(false)
                    .build()
            notificationManager.createNotificationChannel(channel)
            return true
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        goAsync {
            when (intent?.action) {
                ACTION_MARK_NOTIFICATION_READ -> markNotificationRead(context)
                ACTION_NEXT_ARTWORK -> {
                    Firebase.analytics.logEvent(
                            "next_artwork", bundleOf(
                            FirebaseAnalytics.Param.CONTENT_TYPE to "notification"))
                    LegacySourceManager.getInstance(context).nextArtwork()
                }
                ACTION_USER_COMMAND -> triggerUserCommandFromRemoteInput(context, intent)
            }
        }
    }

    private suspend fun triggerUserCommandFromRemoteInput(context: Context, intent: Intent) {
        val selectedCommand = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(EXTRA_USER_COMMAND)?.toString()
                ?: return
            val artwork = MuzeiDatabase.getInstance(context).artworkDao()
                    .getCurrentArtwork()
            if (artwork != null) {
                val commands = artwork.getCommands(context)
                commands.find { selectedCommand == it.title }?.run {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, artwork.providerAuthority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, title.toString())
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "notification")
                    }
                    try {
                        actionIntent.sendFromBackground()
                    } catch (e: PendingIntent.CanceledException) {
                        // Why do you give us a cancelled PendingIntent.
                        // We can't do anything with that.
                    }
                }
            }
    }
}