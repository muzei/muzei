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
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.widget.Toast;

import com.google.android.apps.muzei.MuzeiWallpaperService;
import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
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
public class NextArtworkTileService extends TileService implements LifecycleOwner {
    private LifecycleRegistry mLifecycle;
    private LiveData<Source> mSourceLiveData;
    private boolean mWallpaperActive = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mLifecycle = new LifecycleRegistry(this);
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onTileAdded() {
        FirebaseAnalytics.getInstance(this).logEvent("tile_next_artwork_added", null);
    }

    @Override
    public void onStartListening() {
        // Start listening for source changes, which will include when a source
        // starts or stops supporting the 'Next Artwork' command
        mSourceLiveData = MuzeiDatabase.getInstance(this).sourceDao().getCurrentSource();
        mSourceLiveData.observe(this, new Observer<Source>() {
            @Override
            public void onChanged(@Nullable final Source source) {
                updateTile(source);
            }
        });

        // Check if the wallpaper is currently active
        EventBus.getDefault().register(this);
        WallpaperActiveStateChangedEvent e = EventBus.getDefault().getStickyEvent(
                WallpaperActiveStateChangedEvent.class);
        mWallpaperActive = e != null && e.isActive();
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
    }

    @Subscribe
    public void onEventMainThread(final WallpaperActiveStateChangedEvent e) {
        mWallpaperActive = e != null && e.isActive();
        updateTile(mSourceLiveData.getValue());
    }

    private void updateTile(Source source) {
        Tile tile = getQsTile();
        if (tile == null) {
            // We're outside of the onStartListening / onStopListening window
            // We'll update the tile next time onStartListening is called.
            return;
        }
        if (!mWallpaperActive && tile.getState() != Tile.STATE_INACTIVE) {
            // If the wallpaper isn't active, the quick tile will activate it
            tile.setState(Tile.STATE_INACTIVE);
            tile.setLabel(getString(R.string.action_activate));
            tile.setIcon(Icon.createWithResource(this, R.drawable.ic_stat_muzei));
            tile.updateTile();
            return;
        }
        if (source == null) {
            return;
        }
        if (source.supportsNextArtwork) {
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
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP);
    }

    @Override
    public void onTileRemoved() {
        FirebaseAnalytics.getInstance(this).logEvent("tile_next_artwork_removed", null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
    }
}
