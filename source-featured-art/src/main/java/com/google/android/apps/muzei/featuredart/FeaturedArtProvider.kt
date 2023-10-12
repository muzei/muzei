/*
 * Copyright 2018 Google Inc.
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

package com.google.android.apps.muzei.featuredart

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.RemoteActionCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.apps.muzei.api.R as ApiR
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.util.getString

class FeaturedArtProvider : MuzeiArtProvider() {

    companion object {
        private const val TAG = "FeaturedArtProvider"
        private val ARCHIVE_URI = Uri.parse("http://muzei.co/archive")
    }

    @SuppressLint("Recycle")
    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "onLoadRequested: $initial")
        }
        if (initial) {
            // Show the initial photo (starry night)
            addArtwork(Artwork.Builder()
                    .token("initial")
                    .title("The Starry Night")
                    .byline("Vincent van Gogh, 1889.\nMuzei shows a new painting every day.")
                    .attribution("wikiart.org")
                    .persistentUri(Uri.parse("file:///android_asset/starrynight.jpg"))
                    .webUri(Uri.parse("http://www.wikiart.org/en/vincent-van-gogh/the-starry-night-1889"))
                    .build())
        } else {
            // Delete all but the latest artwork to avoid
            // cycling through all of the previously Featured Art
            query(contentUri, null, null, null, null).use { data ->
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Found ${data.count} existing artwork")
                }
                if (data.count > 1 && data.moveToFirst()) {
                    val count = delete(contentUri, BaseColumns._ID + " != ?",
                            arrayOf(data.getString(BaseColumns._ID)))
                    if (BuildConfig.DEBUG) {
                        Log.d(TAG, "Deleted $count")
                    }
                }
            }
        }
        FeaturedArtWorker.enqueueLoad(context)
    }

    override fun getCommandActions(artwork: Artwork): List<RemoteActionCompat> {
        val context = context ?: return super.getCommandActions(artwork)
        if (!context.resources.getBoolean(R.bool.featuredart_supports_actions)) {
            return emptyList()
        }
        return listOf(
                createShareAction(context, artwork),
                createViewArchiveAction(context))
    }

    @SuppressLint("InlinedApi")
    private fun createShareAction(context: Context, artwork: Artwork) : RemoteActionCompat {
        val title = context.getString(R.string.featuredart_action_share_artwork)
        val artist = artwork.byline
                ?.replaceFirst("\\.\\s*($|\\n).*", "")
                ?.trim()
        val intent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "My Android wallpaper today is '"
                    + artwork.title?.trim { it <= ' ' }
                    + "' by $artist. #MuzeiFeaturedArt\n\n${artwork.webUri}")
        }, "Share artwork")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return RemoteActionCompat(
                IconCompat.createWithResource(context, R.drawable.featuredart_share),
                title,
                title,
                PendingIntent.getActivity(context, artwork.id.toInt(), intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    @SuppressLint("InlinedApi")
    private fun createViewArchiveAction(context: Context) : RemoteActionCompat {
        val title = context.getString(R.string.featuredart_source_action_view_archive)
        val cti = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder()
                        .setToolbarColor(ContextCompat.getColor(context, R.color.featuredart_color))
                        .build())
                .build()
        val intent = cti.intent.apply {
            data = ARCHIVE_URI
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return RemoteActionCompat(
                IconCompat.createWithResource(context, ApiR.drawable.muzei_launch_command),
                title,
                title,
                PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).apply {
            setShouldShowIcon(false)
        }
    }
}