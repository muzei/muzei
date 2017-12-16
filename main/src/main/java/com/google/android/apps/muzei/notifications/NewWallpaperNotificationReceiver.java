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

package com.google.android.apps.muzei.notifications;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.media.ExifInterface;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.render.BitmapRegionLoader;
import com.google.android.apps.muzei.render.ImageUtil;
import com.google.android.apps.muzei.room.Artwork;
import com.google.android.apps.muzei.room.ArtworkSource;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class NewWallpaperNotificationReceiver extends BroadcastReceiver {
    private static final String TAG = "NewWallpaperNotif";

    public static final String PREF_ENABLED = "new_wallpaper_notification_enabled";
    private static final String PREF_LAST_READ_NOTIFICATION_ARTWORK_ID
            = "last_read_notification_artwork_id";
    private static final String PREF_LAST_READ_NOTIFICATION_ARTWORK_IMAGE_URI
            = "last_read_notification_artwork_image_uri";
    private static final String PREF_LAST_READ_NOTIFICATION_ARTWORK_TOKEN
            = "last_read_notification_artwork_token";

    static final String NOTIFICATION_CHANNEL = "new_wallpaper";
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
                SourceManager.sendAction(context, MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
            } else if (ACTION_USER_COMMAND.equals(action)) {
                triggerUserCommandFromRemoteInput(context, intent);
            }
        }
    }

    private void triggerUserCommandFromRemoteInput(final Context context, Intent intent) {
        Bundle remoteInput = RemoteInput.getResultsFromIntent(intent);
        if (remoteInput == null) {
            return;
        }
        final String selectedCommand = remoteInput.getCharSequence(EXTRA_USER_COMMAND).toString();
        final PendingResult pendingResult = goAsync();
        final LiveData<Source> sourceLiveData = MuzeiDatabase.getInstance(context).sourceDao().getCurrentSource();
        sourceLiveData.observeForever(new Observer<Source>() {
            @Override
            public void onChanged(@Nullable final Source selectedSource) {
                sourceLiveData.removeObserver(this);
                if (selectedSource != null) {
                    for (UserCommand action : selectedSource.commands) {
                        if (TextUtils.equals(selectedCommand, action.getTitle())) {
                            SourceManager.sendAction(context, action.getId());
                            break;
                        }
                        pendingResult.finish();
                    }
                }
            }
        });
    }

    public static void markNotificationRead(final Context context) {
        final LiveData<Artwork> artworkLiveData = MuzeiDatabase.getInstance(context).artworkDao().getCurrentArtwork();
        artworkLiveData.observeForever(new Observer<Artwork>() {
            @Override
            public void onChanged(@Nullable final Artwork lastArtwork) {
                artworkLiveData.removeObserver(this);
                if (lastArtwork != null) {
                    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
                    sp.edit()
                            .putLong(PREF_LAST_READ_NOTIFICATION_ARTWORK_ID, lastArtwork.id)
                            .putString(PREF_LAST_READ_NOTIFICATION_ARTWORK_IMAGE_URI,
                                    lastArtwork.imageUri != null
                                            ? lastArtwork.imageUri.toString()
                                            : null)
                            .putString(PREF_LAST_READ_NOTIFICATION_ARTWORK_TOKEN, lastArtwork.token)
                            .apply();
                }

                cancelNotification(context);
            }
        });
    }

    public static void cancelNotification(Context context) {
        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.cancel(NOTIFICATION_ID);
    }

    static boolean isNewWallpaperNotificationEnabled(@NonNull Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // On O+ devices, we defer to the system setting
            if (!createNotificationChannel(context)) {
                // Don't post the new wallpaper notification in the case where
                // we've also posted the 'Review your settings' notification
                return false;
            }
            NotificationManager notificationManager = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return false;
            }
            NotificationChannel channel = notificationManager
                    .getNotificationChannel(NOTIFICATION_CHANNEL);
            return channel != null &&
                    channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        // Prior to O, we maintain our own preference
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        return sp.getBoolean(PREF_ENABLED, true);
    }

    public static void maybeShowNewArtworkNotification(Context context) {
        ArtDetailOpenedClosedEvent adoce = EventBus.getDefault().getStickyEvent(
                ArtDetailOpenedClosedEvent.class);
        if (adoce != null && adoce.isArtDetailOpened()) {
            return;
        }

        if (!isNewWallpaperNotificationEnabled(context)) {
            return;
        }

        ContentResolver contentResolver = context.getContentResolver();
        ArtworkSource artworkSource = MuzeiDatabase.getInstance(context)
                .artworkDao()
                .getCurrentArtworkWithSourceBlocking();
        if (artworkSource == null) {
            return;
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        long currentArtworkId = artworkSource.artwork.id;
        long lastReadArtworkId = sp.getLong(PREF_LAST_READ_NOTIFICATION_ARTWORK_ID, -1);
        String currentImageUri = artworkSource.artwork.imageUri != null
                ? artworkSource.artwork.imageUri.toString()
                : null;
        String lastReadImageUri = sp.getString(PREF_LAST_READ_NOTIFICATION_ARTWORK_IMAGE_URI, null);
        String currentToken = artworkSource.artwork.token;
        String lastReadToken = sp.getString(PREF_LAST_READ_NOTIFICATION_ARTWORK_TOKEN, null);
        // We've already dismissed the notification if the IDs match
        boolean previouslyDismissedNotification = lastReadArtworkId == currentArtworkId;
        // We've already dismissed the notification if the image URIs match and both are not empty
        previouslyDismissedNotification = previouslyDismissedNotification ||
                (!TextUtils.isEmpty(lastReadImageUri) && !TextUtils.isEmpty(currentImageUri) &&
                    TextUtils.equals(lastReadImageUri, currentImageUri));
        // We've already dismissed the notification if the tokens match and both are not empty
        previouslyDismissedNotification = previouslyDismissedNotification ||
                (!TextUtils.isEmpty(lastReadToken) && !TextUtils.isEmpty(currentToken) &&
                        TextUtils.equals(lastReadToken, currentToken));
        if (previouslyDismissedNotification) {
            return;
        }

        Bitmap largeIcon;
        Bitmap background;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            // Check if there's rotation
            int rotation = 0;
            try (InputStream in = contentResolver.openInputStream(
                    MuzeiContract.Artwork.CONTENT_URI)) {
                if (in == null) {
                    return;
                }
                ExifInterface exifInterface = new ExifInterface(in);
                int orientation = exifInterface.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                switch (orientation) {
                    case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                    case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                    case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                }
            } catch (IOException |NumberFormatException|StackOverflowError e) {
                Log.w(TAG, "Couldn't open EXIF interface on artwork", e);
            }
            BitmapRegionLoader regionLoader = BitmapRegionLoader.newInstance(
                    contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI), rotation);
            int width = regionLoader.getWidth();
            int height = regionLoader.getHeight();
            int shortestLength = Math.min(width, height);
            options.inJustDecodeBounds = false;
            int largeIconHeight = context.getResources()
                    .getDimensionPixelSize(android.R.dimen.notification_large_icon_height);
            options.inSampleSize = ImageUtil.calculateSampleSize(shortestLength, largeIconHeight);
            largeIcon = regionLoader.decodeRegion(new Rect(0, 0, width, height), options);

            // Use the suggested 400x400 for Android Wear background images per
            // http://developer.android.com/training/wearables/notifications/creating.html#AddWearableFeatures
            options.inSampleSize = ImageUtil.calculateSampleSize(height, 400);
            background = regionLoader.decodeRegion(new Rect(0, 0, width, height), options);
        } catch (IOException e) {
            Log.e(TAG, "Unable to load artwork to show notification", e);
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context);
        }

        String artworkTitle = artworkSource.artwork.title;
        String title = TextUtils.isEmpty(artworkTitle)
                ? context.getString(R.string.app_name)
                : artworkTitle;
        NotificationCompat.Builder nb = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_muzei)
                .setColor(ContextCompat.getColor(context, R.color.notification))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setAutoCancel(true)
                .setContentTitle(title)
                .setContentText(context.getString(R.string.notification_new_wallpaper))
                .setLargeIcon(largeIcon)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                        new Intent(context, NewWallpaperNotificationReceiver.class)
                                .setAction(ACTION_MARK_NOTIFICATION_READ),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        NotificationCompat.BigPictureStyle style = new NotificationCompat.BigPictureStyle()
                .bigLargeIcon(null)
                .setBigContentTitle(title)
                .setSummaryText(artworkSource.artwork.byline)
                .bigPicture(background);
        nb.setStyle(style);

        NotificationCompat.WearableExtender extender = new NotificationCompat.WearableExtender();

        // Support Next Artwork
        if (artworkSource.supportsNextArtwork) {
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
        }
        List<UserCommand> commands = artworkSource.commands;
        // Show custom actions as a selectable list on Android Wear devices
        if (!commands.isEmpty()) {
            String[] actions = new String[commands.size()];
            for (int h=0; h<commands.size(); h++) {
                actions[h] = commands.get(h).getTitle();
            }
            PendingIntent userCommandPendingIntent = PendingIntent.getBroadcast(context, 0,
                    new Intent(context, NewWallpaperNotificationReceiver.class)
                            .setAction(ACTION_USER_COMMAND),
                    PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput remoteInput = new RemoteInput.Builder(EXTRA_USER_COMMAND)
                    .setAllowFreeFormInput(false)
                    .setLabel(context.getString(R.string.action_user_command_prompt))
                    .setChoices(actions)
                    .build();
            extender.addAction(new NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_full_user_command,
                    context.getString(R.string.action_user_command),
                    userCommandPendingIntent).addRemoteInput(remoteInput)
                    .extend(new NotificationCompat.Action.WearableExtender().setAvailableOffline(false))
                    .build());
        }
        Intent viewIntent = artworkSource.artwork.viewIntent;
        if (viewIntent != null) {
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                PendingIntent nextPendingIntent = PendingIntent.getActivity(context, 0,
                        viewIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);
                nb.addAction(
                        R.drawable.ic_notif_info,
                        context.getString(R.string.action_artwork_info),
                        nextPendingIntent);
                // Android Wear uses larger action icons so we build a
                // separate action
                extender.addAction(new NotificationCompat.Action.Builder(
                        R.drawable.ic_notif_full_info,
                        context.getString(R.string.action_artwork_info),
                        nextPendingIntent)
                        .extend(new NotificationCompat.Action.WearableExtender()
                                .setAvailableOffline(false))
                        .build());
            } catch (RuntimeException ignored) {
                // This is actually meant to catch a FileUriExposedException, but you can't
                // have catch statements for exceptions that don't exist at your minSdkVersion
            }
        }
        nb.extend(extender);

        // Hide the image and artwork title for the public version
        NotificationCompat.Builder publicBuilder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_muzei)
                .setColor(ContextCompat.getColor(context, R.color.notification))
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setAutoCancel(true)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_new_wallpaper))
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        context.getPackageManager().getLaunchIntentForPackage(context.getPackageName()),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setDeleteIntent(PendingIntent.getBroadcast(context, 0,
                        new Intent(context, NewWallpaperNotificationReceiver.class)
                                .setAction(ACTION_MARK_NOTIFICATION_READ),
                        PendingIntent.FLAG_UPDATE_CURRENT));
        nb.setPublicVersion(publicBuilder.build());


        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
        nm.notify(NOTIFICATION_ID, nb.build());
    }

    /**
     * Create the notification channel for the New Wallpaper notification
     * @return False only in the case where the user had wallpapers disabled in-app, but has not
     * yet seen the 'Review your notification settings' notification
     */
    @RequiresApi(api = Build.VERSION_CODES.O)
    static boolean createNotificationChannel(Context context) {
        NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        // On O+ devices, we want to push users to change the system notification setting
        // but we'll use their current value to set the default importance
        int defaultImportance = sp.getBoolean(PREF_ENABLED, true)
                ? NotificationManager.IMPORTANCE_MIN
                : NotificationManager.IMPORTANCE_NONE;
        if (sp.contains(PREF_ENABLED)) {
            sp.edit().remove(PREF_ENABLED).apply();
            if (defaultImportance == NotificationManager.IMPORTANCE_NONE) {
                // Check to see if there was already a channel and give users an
                // easy way to review their notification settings if they had
                // previously disabled notifications but have not yet disabled
                // the channel
                NotificationChannel existingChannel = notificationManager
                        .getNotificationChannel(NOTIFICATION_CHANNEL);
                if (existingChannel != null &&
                        existingChannel.getImportance() != NotificationManager.IMPORTANCE_NONE) {
                    // Construct an Intent to get to the notification settings screen
                    Intent settingsIntent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
                    settingsIntent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                    settingsIntent.putExtra(Settings.EXTRA_CHANNEL_ID,
                            NewWallpaperNotificationReceiver.NOTIFICATION_CHANNEL);
                    // Build the notification
                    NotificationCompat.Builder builder = new NotificationCompat.Builder(context, NOTIFICATION_CHANNEL)
                            .setSmallIcon(R.drawable.ic_stat_muzei)
                            .setColor(ContextCompat.getColor(context, R.color.notification))
                            .setAutoCancel(true)
                            .setContentTitle(context.getText(R.string.notification_settings_moved_title))
                            .setContentText(context.getText(R.string.notification_settings_moved_text))
                            .setContentIntent(PendingIntent.getActivity(context, 0,
                                    settingsIntent,
                                    PendingIntent.FLAG_UPDATE_CURRENT))
                            .setStyle(new NotificationCompat.BigTextStyle()
                                    .bigText(context.getText(R.string.notification_settings_moved_text)));
                    notificationManager.notify(1, builder.build());
                    return false;
                }
            }
        }
        NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL,
                context.getString(R.string.notification_new_wallpaper_channel_name),
                defaultImportance);
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
        return true;
    }
}
