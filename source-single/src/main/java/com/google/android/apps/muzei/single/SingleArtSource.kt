/*
 * Copyright 2017 Google Inc.
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

package com.google.android.apps.muzei.single

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import com.google.android.apps.muzei.api.Artwork
import com.google.android.apps.muzei.api.MuzeiArtSource
import com.google.android.apps.muzei.api.provider.ProviderContract

/**
 * [MuzeiArtSource] that displays just a single image
 */
class SingleArtSource : MuzeiArtSource("SingleArtSource") {

    companion object {
        private const val ACTION_MIGRATE = "migrate"

        fun migrateToProvider(context: Context) {
            context.startService(Intent(context, SingleArtSource::class.java).apply {
                action = ACTION_MIGRATE
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_MIGRATE) {
            currentArtwork?.let { currentArtwork ->
                ProviderContract.Artwork.setArtwork(this,
                        SingleArtProvider::class.java,
                        com.google.android.apps.muzei.api.provider.Artwork().apply {
                            title = currentArtwork.title
                        })
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUpdate(reason: Int) {
        val artwork = ProviderContract.Artwork.getLastAddedArtwork(this,
                SingleArtProvider::class.java)
        if (artwork != null) {
            publishArtwork(Artwork.Builder()
                    .title(artwork.title)
                    .imageUri(ContentUris.withAppendedId(
                            ProviderContract.Artwork.getContentUri(this, SingleArtProvider::class.java),
                            artwork.id))
                    .build())
        }
    }
}
