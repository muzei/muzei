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

package com.google.android.apps.muzei;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.render.BitmapRegionLoader;
import com.google.android.apps.muzei.render.ImageUtil;

import net.nurik.roman.muzei.R;

import java.util.ArrayList;

import de.greenrobot.event.EventBus;

public class NewWallpaperNotificationReceiver extends BroadcastReceiver {
    public static final String PREF_ENABLED = "new_wallpaper_notification_enabled";
    private static final String PREF_LAST_SEEN_NOTIFICATION_IMAGE_URI
            = "last_seen_notification_image_uri";

    private static final int NOTIFICATION_ID = 1234;

    private static final String ACTION_MARK_NOTIFICATION_READ
            = "com.google.android.apps.muzei.action.NOTIFICATION_DELETED";

    private static final String ACTION_NEXT_ARTWORK
            = "com.google.android.apps.muzei.action.NOTIFICATION_NEXT_ARTWORK";

    private static final String ACTION_USER_COMMAND
            = "com.google.android.apps.muzei.action.NOTIFICATION_USER_COMMAND";

    private static final String EXTRA_USER_COMMAND
            = "com.google.android.apps.muzei.extra.USER_COMMAND";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_MARK_NOTIFICATION_READ.equals(action)) {
                markNotificationRead(context);
            } else if (ACTION_NEXT_ARTWORK.equals(action)) {
                SourceManager sm = SourceManager.getInstance(context);
                sm.sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
            } else if (ACTION_USER_COMMAND.equals(action)) {
                triggerUserCommandFromRemoteInput(context, intent);
            }
        }
    }

    private void triggerUserCommandFromRemoteInput(Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput == null) {
            return;
        }
        String selectedCommand = remoteInput.getCharSequence(EXTRA_USER_COMMAND).toString();
        SourceManager sm = SourceManager.getInstance(context);
        SourceState state = sm.getSelectedSourceState();
        for (int i = 0; i < state.getNumUserCommands(); i++) {
            UserCommand action = state.getUserCommandAt(i);
            if (TextUtils.equals(selectedCommand, action.getTitle())) {
                sm.sendAction(action.getId());
                break;
            }
        }
    }

    public static void markNotificationRead(Context context) {
        SourceManager sm = SourceManager.getInstance(context);
        SourceState state = sm.getSelectedSourceState();
        Artwork currentArtwork = (state == null) ? null : state.getCurrentArtwork();
        if (currentArtwork == null || currentArtwork.getImageUri() == null) {
            return;
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        sp.edit().putString(PREF_LAST_SEEN_NOTIFICATION_IMAGE_URI,
                currentArtwork.getImageUri().toString()).apply();

        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NOTIFICATION_ID);
    }

    public static void maybeShowNewArtworkNotification(Context context, Artwork artwork,
            BitmapRegionLoader bitmapRegionLoader) {
        if (artwork == null || artwork.getImageUri() == null || bitmapRegionLoader == null) {
            return;
        }

        ArtDetailOpenedClosedEvent adoce = EventBus.getDefault().getStickyEvent(
                ArtDetailOpenedClosedEvent.class);
        if (adoce != null && adoce.isArtDetailOpened()) {
            return;
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        if (!sp.getBoolean(PREF_ENABLED, true)) {
            return;
        }

        String lastSeenImageUri = sp.getString(PREF_LAST_SEEN_NOTIFICATION_IMAGE_URI, null);
        if (TextUtils.equals(lastSeenImageUri, artwork.getImageUri().toString())) {
            return;
        }

        Rect rect = new Rect();
        int width = bitmapRegionLoader.getWidth();
        int height = bitmapRegionLoader.getHeight();
        if (width > height) {
            rect.set((width - height) / 2, 0, (width + height) / 2, height);
        } else {
            rect.set(0, (height - width) / 2, width, (height + width) / 2);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        int largeIconHeight = context.getResources()
                .getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
        options.inSampleSize = ImageUtil.calculateSampleSize(height, largeIconHeight);
        Bitmap largeIcon = bitmapRegionLoader.decodeRegion(rect, options);
        if (largeIcon == null) {
            // decodeRegion should always return something for valid images
            // Assume this is a temporary issue and try again later
            return;
        }

        // Use the suggested 400x400 for Android Wear background images per
        // http://developer.android.com/training/wearables/notifications/creating.html#AddWearableFeatures
        options.inSampleSize = ImageUtil.calculateSampleSize(height, 400);
        Bitmap background = bitmapRegionLoader.decodeRegion(rect, options);
        if (background == null) {
            // decodeRegion should always return something for valid images
            // Assume this is a temporary issue and try again later
            return;
        }

        String title = TextUtils.isEmpty(artwork.getTitle())
                ? context.getString(R.string.app_name)
                : artwork.getTitle();
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_stat_muzei)
                .setColor(context.getResources().getColor(R.color.notification))
                .setPriority(Notification.PRIORITY_MIN)
                .setAutoCancel(true)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notification_new_wallpaper))
                .setLargeIcon(largeIcon)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        Intent.makeMainActivity(new ComponentName(context, MuzeiActivity.class)),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                        new Intent(context, NewWallpaperNotificationReceiver.class)
                                .setAction(ACTION_MARK_NOTIFICATION_READ),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        NotificationCompat.BigPictureStyle style = new NotificationCompat.BigPictureStyle()
                .bigLargeIcon(null)
                .setBigContentTitle(title)
                .setSummaryText(artwork.getByline())
                .bigPicture(background);
        nb.setStyle(style);

        NotificationCompat.WearableExtender extender = new NotificationCompat.WearableExtender();
        SourceManager sm = SourceManager.getInstance(context);
        SourceState state = sm.getSelectedSourceState();
        ArrayList<String> customActions = new ArrayList<>();
        for (int i = 0; i < state.getNumUserCommands(); i++) {
            UserCommand action = state.getUserCommandAt(i);
            if (action.getId() == MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
                PendingIntent nextPendingIntent = PendingIntent.getBroadcast(context, 0,
                        new Intent(context, NewWallpaperNotificationReceiver.class)
                                .setAction(ACTION_NEXT_ARTWORK),
                        PendingIntent.FLAG_UPDATE_CURRENT);
                nb.addAction(
                        R.drawable.ic_notif_next_artwork,
                        context.getString(R.string.action_next_artwork_condensed),
                        nextPendingIntent);
                // Android Wear uses larger action icons so we build a
                // separate action
                extender.addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_notif_full_next_artwork,
                        context.getString(R.string.action_next_artwork_condensed),
                        nextPendingIntent)
                        .extend(new NotificationCompat.Action.WearableExtender().setAvailableOffline(false))
                        .build());
            } else {
                customActions.add(action.getTitle());
            }
        }
        // Show custom actions as a selectable list on Android Wear devices
        if (!customActions.isEmpty()) {
            PendingIntent userCommandPendingIntent = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, NewWallpaperNotificationReceiver.class)
                            .setAction(ACTION_USER_COMMAND),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput remoteInput = new RemoteInput.Builder(EXTRA_USER_COMMAND)
                    .setAllowFreeFormInput(false)
                    .setLabel(context.getString(R.string.action_user_command_prompt))
                    .setChoices(customActions.toArray(new String[customActions.size()]))
                    .build();
            extender.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_full_user_command,
                    context.getString(R.string.action_user_command),
                    userCommandPendingIntent).addRemoteInput(remoteInput)
                    .extend(new NotificationCompat.Action.WearableExtender().setAvailableOffline(false))
                    .build());
        }
        Intent viewIntent = artwork.getViewIntent();
        if (viewIntent != null) {
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent nextPendingIntent = PendingIntent.getActivity(context, 0,
                    viewIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            nb.addAction(
                    R.drawable.ic_notif_info,
                    context.getString(R.string.action_open_details),
                    nextPendingIntent);
            // Android Wear uses larger action icons so we build a
            // separate action
            extender.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_full_info,
                    context.getString(R.string.action_open_details),
                    nextPendingIntent)
                    .extend(new NotificationCompat.Action.WearableExtender().setAvailableOffline(false))
                    .build());
        }
        nb.extend(extender);

        // Hide the image and artwork title for the public version
        NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_stat_muzei)
                .setColor(context.getResources().getColor(R.color.notification))
                .setPriority(Notification.PRIORITY_MIN)
                .setAutoCancel(true)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_new_wallpaper))
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        Intent.makeMainActivity(new ComponentName(context, MuzeiActivity.class)),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                        new Intent(context, NewWallpaperNotificationReceiver.class)
                                .setAction(ACTION_MARK_NOTIFICATION_READ),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        nb.setPublicVersion(publicBuilder.build());


        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.notify(NOTIFICATION_ID, nb.build());

        // Clear any last-seen notification
        sp.edit().remove(PREF_LAST_SEEN_NOTIFICATION_IMAGE_URI).apply();
    }
}
