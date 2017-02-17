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

package com.google.android.apps.muzei.quicksettings;

import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.support.annotation.RequiresApi;
import android.widget.Toast;

import com.google.android.apps.muzei.MuzeiWallpaperService;
import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

/**
 * Quick Settings Tile which allows users quick access to the 'Next Artwork' command, if supported.
 * In cases where Muzei is not activated, the tile also allows users to activate Muzei directly
 * from the tile
 */
@RequiresApi(api = Build.VERSION_CODES.N)
public class NextArtworkTileService extends TileService {
    private ContentObserver mSourceContentObserver;
    private boolean mWallpaperActive = false;

    @Override
    public void onTileAdded() {
        FirebaseAnalytics.getInstance(this).logEvent("tile_next_artwork_added", null);
    }

    @Override
    public void onStartListening() {
        // Start listening for source changes, which will include when a source
        // starts or stops supporting the 'Next Artwork' command
        mSourceContentObserver = new ContentObserver(null) {
            @Override
            public void onChange(final boolean selfChange, final Uri uri) {
                updateTile();
            }
        };
        getContentResolver().registerContentObserver(MuzeiContract.Sources.CONTENT_URI,
                true, mSourceContentObserver);

        // Check if the wallpaper is currently active
        EventBus.getDefault().register(this);
        WallpaperActiveStateChangedEvent e = EventBus.getDefault().getStickyEvent(
                WallpaperActiveStateChangedEvent.class);
        // This will call through to updateTile()
        onEventMainThread(e);
    }

    @Subscribe
    public void onEventMainThread(final WallpaperActiveStateChangedEvent e) {
        mWallpaperActive = e != null && e.isActive();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            // We're outside of the onStartListening / onStopListening window
            // We'll update the tile next time onStartListening is called.
            return;
        }
        if (!mWallpaperActive) {
            // If the wallpaper isn't active, the quick tile will activate it
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.action_activate));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_muzei));
            tile.updateTile();
            return;
        }
        // Else, the wallpaper is active so we query on whether the 'Next Artwork' command
        // is available
        Cursor data = getContentResolver().query(MuzeiContract.Sources.CONTENT_URI,
                new String[]{MuzeiContract.Sources.COLUMN_NAME_SUPPORTS_NEXT_ARTWORK_COMMAND},
                MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + "=1", null, null);
        if (data == null) {
            return;
        }
        boolean supportsNextArtwork = false;
        if (data.moveToFirst()) {
            supportsNextArtwork = data.getInt(0) != 0;
        }
        data.close();
        if (supportsNextArtwork) {
            tile.setState(Tile.STATE_ACTIVE);
            tile.setLabel(getString(R.string.action_next_artwork));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notif_full_next_artwork));
        } else {
            tile.setState(Tile.STATE_UNAVAILABLE);
            tile.setLabel(getString(R.string.action_next_artwork));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_notif_full_next_artwork));
        }
        tile.updateTile();
    }

    @Override
    public void onClick() {
        Tile tile = getQsTile();
        if (tile == null) {
            // We're outside of the onStartListening / onStopListening window,
            // ignore late arriving clicks
            return;
        }
        if (tile.getState() == Tile.STATE_ACTIVE) {
            FirebaseAnalytics.getInstance(NextArtworkTileService.this).logEvent(
                    "tile_next_artwork_click", null);
            // Active means we send the 'Next Artwork' command
            SourceManager.sendAction(this, MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
        } else {
            // Inactive means we attempt to activate Muzei
            unlockAndRun(new Runnable() {
                @Override
                public void run() {
                    FirebaseAnalytics.getInstance(NextArtworkTileService.this).logEvent(
                            "tile_next_artwork_activate", null);
                    try {
                        startActivityAndCollapse(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                        new ComponentName(NextArtworkTileService.this,
                                                MuzeiWallpaperService.class))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException e) {
                        try {
                            startActivityAndCollapse(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                        } catch (ActivityNotFoundException e2) {
                            Toast.makeText(NextArtworkTileService.this, R.string.error_wallpaper_chooser,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                }
            });
        }
    }

    @Override
    public void onStopListening() {
        EventBus.getDefault().unregister(this);
        getContentResolver().unregisterContentObserver(mSourceContentObserver);
    }

    @Override
    public void onTileRemoved() {
        FirebaseAnalytics.getInstance(this).logEvent("tile_next_artwork_removed", null);
    }
}
