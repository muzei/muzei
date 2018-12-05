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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.os.Build
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.bundleOf
import com.google.android.apps.muzei.ArtDetailOpenLiveData
import com.google.android.apps.muzei.ArtworkInfoRedirectActivity
import com.google.android.apps.muzei.render.ContentUriImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.room.sendAction
import com.google.android.apps.muzei.sources.SourceManager
import com.google.android.apps.muzei.sources.allowsNextArtwork
import com.google.android.apps.muzei.util.goAsync
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

class NewWallpaperNotificationReceiver : BroadcastReceiver() {

    companion object {
        const val PREF_ENABLED = "new_wallpaper_notification_enabled"
        private const val PREF_LAST_READ_NOTIFICATION_ARTWORK_ID = "last_read_notification_artwork_id"

        internal const val NOTIFICATION_CHANNEL = "new_wallpaper"
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
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
                        ?: return false
                val channel = notificationManager
                        .getNotificationChannel(NOTIFICATION_CHANNEL)
                return channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
            }
            // Prior to O, we maintain our own preference
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            return sp.getBoolean(PREF_ENABLED, true)
        }

        suspend fun maybeShowNewArtworkNotification(context: Context) {
            if (ArtDetailOpenLiveData.value == true) {
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
            val largeIcon = imageLoader.decode(largeIconHeight) ?: return
            val bigPicture = imageLoader.decode(400) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                createNotificationChannel(context)
            }

            try {
                ContextCompat.getDrawable(context, R.drawable.ic_stat_muzei)
            } catch (e : Resources.NotFoundException) {
                Log.e("Notification", "Invalid installation: " +
                        "missing notification icon", e)
                return
            }
            val artworkTitle = artwork.title
            val title = artworkTitle?.takeUnless { it.isEmpty() }
                    ?: context.getString(R.string.app_name)
            val nb = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, R.color.notification))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setAutoCancel(true)
                    .setContentTitle(title)
                    .setContentText(context.getString(R.string.notification_new_wallpaper))
                    .setLargeIcon(largeIcon)
                    .setContentIntent(PendingIntent.getActivity(context, 0,
                            context.packageManager.getLaunchIntentForPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT))
                    .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                            Intent(context, NewWallpaperNotificationReceiver::class.java)
                                    .setAction(ACTION_MARK_NOTIFICATION_READ),
                            PendingIntent.FLAG_UPDATE_CURRENT))
            val style = NotificationCompat.BigPictureStyle()
                    .bigLargeIcon(null)
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
                        PendingIntent.FLAG_UPDATE_CURRENT)
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
            if (!commands.isEmpty()) {
                val actions = commands.map { it.title }.toTypedArray()
                val userCommandPendingIntent = PendingIntent.getBroadcast(context, 0,
                        Intent(context, NewWallpaperNotificationReceiver::class.java)
                                .setAction(ACTION_USER_COMMAND),
                        PendingIntent.FLAG_UPDATE_CURRENT)
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
                    PendingIntent.FLAG_UPDATE_CURRENT)
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
                    .setSmallIcon(R.drawable.ic_stat_muzei)
                    .setColor(ContextCompat.getColor(context, R.color.notification))
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setAutoCancel(true)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText(context.getString(R.string.notification_new_wallpaper))
                    .setContentIntent(PendingIntent.getActivity(context, 0,
                            context.packageManager.getLaunchIntentForPackage(context.packageName),
                            PendingIntent.FLAG_UPDATE_CURRENT))
                    .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                            Intent(context, NewWallpaperNotificationReceiver::class.java)
                                    .setAction(ACTION_MARK_NOTIFICATION_READ),
                            PendingIntent.FLAG_UPDATE_CURRENT))
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
        @RequiresApi(Build.VERSION_CODES.O)
        internal fun createNotificationChannel(context: Context): Boolean {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
                    ?: return false
            val sp = PreferenceManager.getDefaultSharedPreferences(context)
            // On O+ devices, we want to push users to change the system notification setting
            // but we'll use their current value to set the default importance
            val defaultImportance = if (sp.getBoolean(PREF_ENABLED, true))
                NotificationManager.IMPORTANCE_MIN
            else
                NotificationManager.IMPORTANCE_NONE
            if (sp.contains(PREF_ENABLED)) {
                sp.edit { remove(PREF_ENABLED) }
                if (defaultImportance == NotificationManager.IMPORTANCE_NONE) {
                    // Check to see if there was already a channel and give users an
                    // easy way to review their notification settings if they had
                    // previously disabled notifications but have not yet disabled
                    // the channel
                    val existingChannel = notificationManager
                            .getNotificationChannel(NOTIFICATION_CHANNEL)
                    if (existingChannel != null && existingChannel.importance != NotificationManager.IMPORTANCE_NONE) {
                        // Construct an Intent to get to the notification settings screen
                        val settingsIntent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        settingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        settingsIntent.putExtra(Settings.EXTRA_CHANNEL_ID,
                                NewWallpaperNotificationReceiver.NOTIFICATION_CHANNEL)
                        // Build the notification
                        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                                .setSmallIcon(R.drawable.ic_stat_muzei)
                                .setColor(ContextCompat.getColor(context, R.color.notification))
                                .setAutoCancel(true)
                                .setContentTitle(context.getText(R.string.notification_settings_moved_title))
                                .setContentText(context.getText(R.string.notification_settings_moved_text))
                                .setContentIntent(PendingIntent.getActivity(context, 0,
                                        settingsIntent,
                                        PendingIntent.FLAG_UPDATE_CURRENT))
                                .setStyle(NotificationCompat.BigTextStyle()
                                        .bigText(context.getText(R.string.notification_settings_moved_text)))
                        notificationManager.notify(1, builder.build())
                        return false
                    }
                }
            }
            val channel = NotificationChannel(NOTIFICATION_CHANNEL,
                    context.getString(R.string.notification_new_wallpaper_channel_name),
                    defaultImportance)
            channel.setShowBadge(false)
            notificationManager.createNotificationChannel(channel)
            return true
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        goAsync {
            when (intent?.action) {
                ACTION_MARK_NOTIFICATION_READ -> markNotificationRead(context)
                ACTION_NEXT_ARTWORK -> {
                    FirebaseAnalytics.getInstance(context).logEvent(
                            "next_artwork", bundleOf(
                            FirebaseAnalytics.Param.CONTENT_TYPE to "notification"))
                    SourceManager.nextArtwork(context)
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
                    FirebaseAnalytics.getInstance(context).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to id,
                            FirebaseAnalytics.Param.ITEM_NAME to title,
                            FirebaseAnalytics.Param.ITEM_CATEGORY to "actions",
                            FirebaseAnalytics.Param.CONTENT_TYPE to "notification"))
                    artwork.sendAction(context, id)
                }
            }
    }
}
