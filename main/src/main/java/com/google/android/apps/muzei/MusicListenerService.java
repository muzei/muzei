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

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.os.Build;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.google.android.apps.muzei.event.MusicArtworkChangedEvent;
import com.google.android.apps.muzei.event.MusicStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;

import de.greenrobot.event.EventBus;

@TargetApi(Build.VERSION_CODES.KITKAT)
public class MusicListenerService extends NotificationListenerService
        implements RemoteController.OnClientUpdateListener {
    public static final String PREF_ENABLED = "show_music_artwork_enabled";

    private RemoteController mRemoteController;
    private Bitmap mCurrentArtwork = null;

    private static boolean isMusicPlaying(final int state) {
        return state == RemoteControlClient.PLAYSTATE_PLAYING ||
                state == RemoteControlClient.PLAYSTATE_BUFFERING ||
                state == RemoteControlClient.PLAYSTATE_SKIPPING_BACKWARDS ||
                state == RemoteControlClient.PLAYSTATE_REWINDING ||
                state == RemoteControlClient.PLAYSTATE_SKIPPING_FORWARDS ||
                state == RemoteControlClient.PLAYSTATE_FAST_FORWARDING;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mRemoteController = new RemoteController(this, this);
        EventBus.getDefault().register(this);
        WallpaperSizeChangedEvent wsce = EventBus.getDefault().getStickyEvent(
                WallpaperSizeChangedEvent.class);
        if (wsce != null) {
            onEventMainThread(wsce);
        }
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.registerRemoteController(mRemoteController);
    }

    @Override
    public void onClientChange(final boolean clearing) {
        if (clearing) {
            mCurrentArtwork = null;
            EventBus.getDefault().postSticky(new MusicStateChangedEvent(false));
            EventBus.getDefault().postSticky(new MusicArtworkChangedEvent(null));
        }
    }

    @Override
    public void onClientPlaybackStateUpdate(final int state) {
        EventBus.getDefault().postSticky(new MusicStateChangedEvent(isMusicPlaying(state)));
    }

    @Override
    public void onClientPlaybackStateUpdate(final int state, final long stateChangeTimeMs,
                                            final long currentPosMs, final float speed) {
        EventBus.getDefault().postSticky(new MusicStateChangedEvent(isMusicPlaying(state)));
    }

    @Override
    public void onClientTransportControlUpdate(final int transportControlFlags) {
    }

    @Override
    public void onClientMetadataUpdate(final RemoteController.MetadataEditor metadataEditor) {
        Bitmap artwork = metadataEditor.getBitmap(RemoteController.MetadataEditor.BITMAP_KEY_ARTWORK, null);
        if (mCurrentArtwork == null || !mCurrentArtwork.sameAs(artwork)) {
            mCurrentArtwork = artwork;
            EventBus.getDefault().postSticky(new MusicArtworkChangedEvent(mCurrentArtwork));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.unregisterRemoteController(mRemoteController);
    }

    public void onEventMainThread(WallpaperSizeChangedEvent wsce) {
        // As most music art is square, we request the largest artwork possible to fill the
        // larger of the two dimensions
        final int artworkSize = Math.max(wsce.getWidth(), wsce.getHeight());
        mRemoteController.setArtworkConfiguration(artworkSize, artworkSize);
    }

    @Override
    public void onNotificationPosted(final StatusBarNotification sbn) {
    }

    @Override
    public void onNotificationRemoved(final StatusBarNotification sbn) {
    }
}
