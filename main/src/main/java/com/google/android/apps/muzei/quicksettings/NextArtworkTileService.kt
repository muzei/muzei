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

package com.google.android.apps.muzei.quicksettings

import android.app.WallpaperManager
import android.arch.lifecycle.Lifecycle
import android.arch.lifecycle.LifecycleOwner
import android.arch.lifecycle.LifecycleRegistry
import android.arch.lifecycle.LiveData
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.support.annotation.RequiresApi
import android.widget.Toast
import com.google.android.apps.muzei.MuzeiWallpaperService
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.Source
import com.google.android.apps.muzei.sources.SourceManager
import com.google.android.apps.muzei.util.observe
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

/**
 * Quick Settings Tile which allows users quick access to the 'Next Artwork' command, if supported.
 * In cases where Muzei is not activated, the tile also allows users to activate Muzei directly
 * from the tile
 */
@RequiresApi(Build.VERSION_CODES.N)
class NextArtworkTileService : TileService(), LifecycleOwner {
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)

    private lateinit var sourceLiveData: LiveData<Source?>

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        WallpaperActiveState.observe(this) {
            updateTile(sourceLiveData.value)
        }
        // Start listening for source changes, which will include when a source
        // starts or stops supporting the 'Next Artwork' command
        sourceLiveData = MuzeiDatabase.getInstance(this).sourceDao().currentSource
        sourceLiveData.observe(this, this::updateTile)
    }

    override fun getLifecycle(): Lifecycle {
        return lifecycleRegistry
    }

    override fun onTileAdded() {
        FirebaseAnalytics.getInstance(this).logEvent("tile_next_artwork_added", null)
    }

    override fun onStartListening() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    private fun updateTile(source: Source?) {
        val context = this
        qsTile?.takeIf { WallpaperActiveState.value != true || source != null }?.apply {
            when {
                WallpaperActiveState.value != true -> {
                    // If the wallpaper isn't active, the quick tile will activate it
                    state = Tile.STATE_INACTIVE
                    label = getString(R.string.action_activate)
                    icon = Icon.createWithResource(context, R.drawable.ic_stat_muzei)
                }
                source?.supportsNextArtwork == true -> {
                    state = Tile.STATE_ACTIVE
                    label = getString(R.string.action_next_artwork)
                    icon = Icon.createWithResource(context, R.drawable.ic_notif_next_artwork)
                }
                else -> {
                    state = Tile.STATE_UNAVAILABLE
                    label = getString(R.string.action_next_artwork)
                    icon = Icon.createWithResource(context, R.drawable.ic_notif_next_artwork)
                }
            }
        }?.updateTile()
    }

    override fun onClick() {
        val context = this
        qsTile?.run {
            when (state) {
                Tile.STATE_ACTIVE -> { // Active means we send the 'Next Artwork' command
                    FirebaseAnalytics.getInstance(context).logEvent(
                            "tile_next_artwork_click", null)
                    SourceManager.sendAction(context, MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK)
                }
                else -> unlockAndRun { // Inactive means we attempt to activate Muzei
                    FirebaseAnalytics.getInstance(context).logEvent(
                            "tile_next_artwork_activate", null)
                    try {
                        startActivityAndCollapse(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                        ComponentName(context,
                                                MuzeiWallpaperService::class.java))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: ActivityNotFoundException) {
                        try {
                            startActivityAndCollapse(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.error_wallpaper_chooser,
                                    Toast.LENGTH_LONG).show()
                        }

                    }
                }
            }
        }
    }

    override fun onStopListening() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onTileRemoved() {
        FirebaseAnalytics.getInstance(this).logEvent("tile_next_artwork_removed", null)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
