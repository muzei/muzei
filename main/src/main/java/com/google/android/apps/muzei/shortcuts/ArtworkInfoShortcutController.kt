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

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import com.google.android.apps.muzei.ArtworkInfoRedirectActivity
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import net.nurik.roman.muzei.R

/**
 * Controller responsible for updating the Artwork Info Shortcut whenever the current artwork changes
 */
@RequiresApi(Build.VERSION_CODES.N_MR1)
class ArtworkInfoShortcutController(
        private val context: Context,
        private val lifecycleOwner: LifecycleOwner
) : DefaultLifecycleObserver {

    companion object {
        private const val ARTWORK_INFO_SHORTCUT_ID = "artwork_info"
    }

    override fun onCreate(owner: LifecycleOwner) {
        MuzeiDatabase.getInstance(context).artworkDao()
                .currentArtwork.observe(lifecycleOwner) { artwork ->
            updateShortcut(artwork)
        }
    }

    private fun updateShortcut(artwork: Artwork?) {
        val shortcutManager = context.getSystemService(ShortcutManager::class.java)
        val dynamicShortcuts = shortcutManager?.dynamicShortcuts ?: return
        var artworkInfoShortcutInfo: ShortcutInfo? = null
        for (shortcutInfo in dynamicShortcuts) {
            if (shortcutInfo.id == ARTWORK_INFO_SHORTCUT_ID) {
                artworkInfoShortcutInfo = shortcutInfo
            }
        }

        if (artwork != null) {
            if (artworkInfoShortcutInfo?.isEnabled == false) {
                // Re-enable a disabled Artwork Info Shortcut
                shortcutManager.enableShortcuts(
                        listOf(ARTWORK_INFO_SHORTCUT_ID))
            }
            val shortcutInfo = ShortcutInfo.Builder(
                    context, ARTWORK_INFO_SHORTCUT_ID)
                    .setIcon(Icon.createWithResource(context,
                            R.drawable.ic_shortcut_artwork_info))
                    .setShortLabel(context.getString(R.string.action_artwork_info))
                    .setIntent(ArtworkInfoRedirectActivity.getIntent(context, "shortcut"))
                    .build()
            shortcutManager.addDynamicShortcuts(
                    listOf(shortcutInfo))
        } else {
            if (artworkInfoShortcutInfo?.isEnabled == false) {
                shortcutManager.disableShortcuts(
                        listOf(ARTWORK_INFO_SHORTCUT_ID),
                        context.getString(R.string.action_artwork_info_disabled))
            }
        }
    }
}
