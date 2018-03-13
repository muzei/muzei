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

package com.google.android.apps.muzei.shortcuts

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.support.annotation.RequiresApi
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.observe
import net.nurik.roman.muzei.R

/**
 * Controller responsible for updating the Artwork Info Shortcut whenever the current artwork changes
 */
@RequiresApi(api = Build.VERSION_CODES.N_MR1)
class ArtworkInfoShortcutController(private val context: Context,
                                    private val lifecycleOwner: LifecycleOwner)
    : DefaultLifecycleObserver {

    companion object {
        private const val ARTWORK_INFO_SHORTCUT_ID = "artwork_info"
    }

    private lateinit var artworkInfoShortcutHandlerThread: HandlerThread
    private lateinit var artworkInfoShortcutHandler: Handler

    override fun onCreate(owner: LifecycleOwner) {
        artworkInfoShortcutHandlerThread = HandlerThread("MuzeiWallpaperService-ArtworkInfoShortcut")
        artworkInfoShortcutHandlerThread.start()
        artworkInfoShortcutHandler = Handler(artworkInfoShortcutHandlerThread.looper)
        MuzeiDatabase.getInstance(context).artworkDao()
                .currentArtwork.observe(lifecycleOwner) {
            artwork -> artworkInfoShortcutHandler.post { updateShortcut(artwork) }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        artworkInfoShortcutHandlerThread.quitSafely()
    }

    private fun updateShortcut(artwork: Artwork?) {
        if (artwork == null) {
            return
        }
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val dynamicShortcuts = shortcutManager?.dynamicShortcuts ?: return
        var artworkInfoShortcutInfo: ShortcutInfo? = null
        for (shortcutInfo in dynamicShortcuts) {
            if (shortcutInfo.id == ARTWORK_INFO_SHORTCUT_ID) {
                artworkInfoShortcutInfo = shortcutInfo
            }
        }

        val viewIntent = artwork.viewIntent
        if (viewIntent != null) {
            if (artworkInfoShortcutInfo != null && !artworkInfoShortcutInfo.isEnabled) {
                // Re-enable a disabled Artwork Info Shortcut
                shortcutManager.enableShortcuts(
                        listOf(ARTWORK_INFO_SHORTCUT_ID))
            }
            val shortcutInfo = ShortcutInfo.Builder(
                    context, ARTWORK_INFO_SHORTCUT_ID)
                    .setIcon(Icon.createWithResource(context,
                            R.drawable.ic_shortcut_artwork_info))
                    .setShortLabel(context.getString(R.string.action_artwork_info))
                    .setIntent(viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    .build()
            shortcutManager.addDynamicShortcuts(
                    listOf(shortcutInfo))
        } else {
            if (artworkInfoShortcutInfo != null && artworkInfoShortcutInfo.isEnabled) {
                shortcutManager.disableShortcuts(
                        listOf(ARTWORK_INFO_SHORTCUT_ID),
                        context.getString(R.string.action_artwork_info_disabled))
            }
        }
    }
}
