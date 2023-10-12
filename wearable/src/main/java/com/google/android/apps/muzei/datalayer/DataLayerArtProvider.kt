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

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteActionCompat
import androidx.core.graphics.drawable.IconCompat
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.gms.base.R as GmsBaseR
import net.nurik.roman.muzei.R
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
        val context = context ?: return
        if (initial) {
            DataLayerLoadWorker.enqueueLoad(context)
        }
    }

    override fun getCommandActions(artwork: Artwork): List<RemoteActionCompat> {
        val context = context ?: return super.getCommandActions(artwork)
        return listOf(createOpenOnPhoneAction(context))
    }

    private fun createOpenOnPhoneAction(context: Context): RemoteActionCompat {
        val title = context.getString(GmsBaseR.string.common_open_on_phone)
        val intent = Intent(context, OpenOnPhoneReceiver::class.java)
        return RemoteActionCompat(
                IconCompat.createWithResource(context, R.drawable.open_on_phone_button),
                title,
                title,
                PendingIntent.getBroadcast(context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(artwork: Artwork): InputStream {
        val context = context ?: throw FileNotFoundException()
        return FileInputStream(getAssetFile(context))
    }
}