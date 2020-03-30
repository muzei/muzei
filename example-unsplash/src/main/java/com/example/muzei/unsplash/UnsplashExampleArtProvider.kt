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

package com.example.muzei.unsplash

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteActionCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.net.toUri
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import java.io.IOException
import java.io.InputStream

class UnsplashExampleArtProvider : MuzeiArtProvider() {

    companion object {
        private const val TAG = "UnsplashExample"
    }

    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return
        UnsplashExampleWorker.enqueueLoad(context)
    }

    override fun getCommandActions(artwork: Artwork): List<RemoteActionCompat> {
        val context = context ?: return super.getCommandActions(artwork)
        return listOfNotNull(
                createViewProfileAction(context, artwork),
                createVisitUnsplashAction(context))
    }

    private fun createViewProfileAction(context: Context, artwork: Artwork): RemoteActionCompat? {
        val profileUri = artwork.metadata?.toUri() ?: return null
        val title = context.getString(R.string.action_view_profile, artwork.byline)
        val intent = Intent(Intent.ACTION_VIEW, profileUri)
        return RemoteActionCompat(
                IconCompat.createWithResource(context, R.drawable.source_ic_profile),
                title,
                title,
                PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun createVisitUnsplashAction(context: Context): RemoteActionCompat {
        val title = context.getString(R.string.action_visit_unsplash)
        val unsplashUri = context.getString(R.string.unsplash_link) +
            ATTRIBUTION_QUERY_PARAMETERS
        val intent = Intent(Intent.ACTION_VIEW, unsplashUri.toUri())
        return RemoteActionCompat(
                IconCompat.createWithResource(context,
                        com.google.android.apps.muzei.api.R.drawable.muzei_launch_command),
                title,
                title,
                PendingIntent.getActivity(context, 0, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT)).apply {
            setShouldShowIcon(false)
        }
    }

    override fun openFile(artwork: Artwork): InputStream {
        return super.openFile(artwork).also {
            artwork.token?.run {
                try {
                    UnsplashService.trackDownload(this)
                } catch (e: IOException) {
                    Log.w(TAG, "Error reporting download to Unsplash", e)
                }
            }
        }
    }
}
