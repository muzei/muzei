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

package com.google.android.apps.muzei.render

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.util.collectIn
import kotlinx.coroutines.flow.filterNotNull

class RealRenderController(
        context: Context,
        renderer: MuzeiBlurRenderer,
        callbacks: Callbacks
) : RenderController(context, renderer, callbacks) {

    /**
     * If there's no artwork yet (as is the case when in Direct Boot), then we
     * use [MuzeiContract.Artwork.CONTENT_URI].
     */
    private var currentArtworkUri = MuzeiContract.Artwork.CONTENT_URI

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        val database = MuzeiDatabase.getInstance(context)
        database.artworkDao().getCurrentArtworkFlow().filterNotNull().collectIn(owner) { artwork ->
            currentArtworkUri = artwork.contentUri
            reloadCurrentArtwork()
        }
        reloadCurrentArtwork()
    }

    override suspend fun openDownloadedCurrentArtwork() =
            ContentUriImageLoader(context.contentResolver, currentArtworkUri)
}
