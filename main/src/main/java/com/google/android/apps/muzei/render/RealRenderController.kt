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

import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.observeNonNull

class RealRenderController(
        context: Context,
        renderer: MuzeiBlurRenderer,
        callbacks: RenderController.Callbacks
) : RenderController(context, renderer, callbacks) {

    private val artworkLiveData = MuzeiDatabase.getInstance(context)
            .artworkDao().currentArtwork

    init {
        reloadCurrentArtwork(false)
    }

    override fun onCreate(owner: LifecycleOwner) {
        super.onCreate(owner)
        artworkLiveData.observeNonNull(owner) {
            reloadCurrentArtwork(false)
        }
    }

    /**
     * Create a [BitmapRegionLoader] for the current artwork. If [artworkLiveData]
     * doesn't have artwork yet (as is the case when in Direct Boot), then we
     * use [MuzeiContract.Artwork.CONTENT_URI].
     */
    override suspend fun openDownloadedCurrentArtwork(forceReload: Boolean) =
            BitmapRegionLoader.newInstance(context.contentResolver,
                    artworkLiveData.value?.contentUri
                            ?: MuzeiContract.Artwork.CONTENT_URI)
}
