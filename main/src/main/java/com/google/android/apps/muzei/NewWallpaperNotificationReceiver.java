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
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.render.BitmapRegionLoader;
import com.google.android.apps.muzei.render.ImageUtil;

import net.nurik.roman.muzei.R;

import de.greenrobot.event.EventBus;

public class NewWallpaperNotificationReceiver extends BroadcastReceiver {
    public static final String PREF_ENABLED = "new_wallpaper_notification_enabled";
    private static final String PREF_LAST_SEEN_NOTIFICATION_IMAGE_URI
            = "last_seen_notification_image_uri";

    private static final int NOTIFICATION_ID = 1234;

    private static final String ACTION_MARK_NOTIFICATION_READ
            = "com.google.android.apps.muzei.action.NOTIFICATION_DELETED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_MARK_NOTIFICATION_READ.equals(action)) {
                markNotificationRead(context);
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

        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
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
        options.inSampleSize = ImageUtil.calculateSampleSize(height, 256);
        Bitmap largeIcon = bitmapRegionLoader.decodeRegion(rect, options);

        NotificationCompat.Builder nb = new NotificationCompat.Builder(context)
                .setSmallIcon(R.drawable.ic_stat_muzei)
                .setColor(context.getResources().getColor(R.color.notification))
                .setPriority(Notification.PRIORITY_MIN)
                .setAutoCancel(true)
                .setContentTitle(artwork.getTitle())
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
                .setBigContentTitle(artwork.getTitle())
                .setSummaryText(artwork.getByline())
                .bigPicture(largeIcon);
        nb.setStyle(style);

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


        NotificationManager nm = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, nb.build());

        // Clear any last-seen notification
        sp.edit().remove(PREF_LAST_SEEN_NOTIFICATION_IMAGE_URI).apply();
    }
}
