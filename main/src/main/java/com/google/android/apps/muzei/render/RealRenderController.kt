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
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import com.google.android.apps.muzei.api.MuzeiContract

class RealRenderController(
        context: Context,
        renderer: MuzeiBlurRenderer,
        callbacks: RenderController.Callbacks
) : RenderController(context, renderer, callbacks) {

    private val contentObserver: ContentObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            reloadCurrentArtwork(false)
        }
    }

    init {
        context.contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, contentObserver)
        reloadCurrentArtwork(false)
    }

    override fun destroy() {
        super.destroy()
        context.contentResolver.unregisterContentObserver(contentObserver)
    }

    override fun openDownloadedCurrentArtwork(forceReload: Boolean): BitmapRegionLoader? =
            BitmapRegionLoader.newInstance(context.contentResolver,
                    MuzeiContract.Artwork.CONTENT_URI)
}
