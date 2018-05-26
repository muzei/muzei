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

package com.google.android.apps.muzei.datalayer

import android.content.Context
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Provider handling art from a connected phone
 */
class DataLayerArtProvider : MuzeiArtProvider() {

    companion object {
        fun getAssetFile(context: Context): File =
                File(context.filesDir, "data_layer")
    }

    override fun onLoadRequested(initial: Boolean) {
        if (initial) {
            DataLayerLoadWorker.enqueueLoad(showNotification = true)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(artwork: Artwork): InputStream {
        val context = context ?: throw FileNotFoundException()
        return FileInputStream(getAssetFile(context))
    }
}
