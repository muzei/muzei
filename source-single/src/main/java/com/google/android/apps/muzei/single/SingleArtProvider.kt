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

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.android.apps.muzei.api.provider.Artwork
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.single.BuildConfig.SINGLE_AUTHORITY
import com.google.android.apps.muzei.util.getStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * [MuzeiArtProvider] that displays just a single image
 */
class SingleArtProvider : MuzeiArtProvider() {

    companion object {

        private const val TAG = "SingleArtwork"

        internal fun getArtworkFile(context: Context): File {
            return File(context.filesDir, "single")
        }

        suspend fun setArtwork(context: Context, artworkUri: Uri): Boolean {
            val tempFile = writeUriToFile(context, artworkUri, getArtworkFile(context))
            if (tempFile != null) {
                ProviderContract.getProviderClient(context, SINGLE_AUTHORITY).setArtwork(
                        Artwork().apply {
                            title = getDisplayName(context, artworkUri)
                                    ?: context.getString(R.string.single_default_artwork_title)
                        })
            }
            return tempFile != null
        }

        @SuppressLint("Recycle")
        private fun getDisplayName(context: Context, artworkUri: Uri): String? {
            if (DocumentsContract.isDocumentUri(context, artworkUri)) {
                try {
                    context.contentResolver.query(artworkUri,
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null, null, null)?.use { data ->
                        if (data.moveToNext()) {
                            return data.getStringOrNull(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        }
                    }
                } catch (e: SecurityException) {
                    // Whelp, I guess no display name for us
                }
            }
            return null
        }

        private suspend fun writeUriToFile(
                context: Context, uri:
                Uri, destFile: File
        ): File? = withContext(Dispatchers.Default) {
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { out ->
                        val directory = File(context.cacheDir, "single").apply {
                            mkdirs()
                        }

                        for (existingTempFile in directory.listFiles()) {
                            existingTempFile.delete()
                        }
                        val filename = StringBuilder().apply {
                            append(uri.scheme).append("_")
                            append(uri.host).append("_")
                            uri.encodedPath?.takeUnless { it.isEmpty() }?.run {
                                append((if (length > 60) substring(length - 60) else this)
                                        .replace('/', '_')).append("_")
                            }
                        }

                        val tempFile = File(directory, filename.toString())
                        FileOutputStream(tempFile).use { tempOut ->
                            val buffer = ByteArray(1024)
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                out.write(buffer, 0, bytes)
                                tempOut.write(buffer, 0, bytes)
                                bytes = input.read(buffer)
                            }
                            return@withContext tempFile
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Unable to read Uri: $uri", e)
            } catch (e: SecurityException) {
                Log.e(TAG, "Unable to read Uri: $uri", e)
            } catch (e: UnsupportedOperationException) {
                Log.e(TAG, "Unable to read Uri: $uri", e)
            }
            null
        }
    }

    override fun onLoadRequested(initial: Boolean) {
        val context = context ?: return
        if (initial) {
            if (getArtworkFile(context).exists()) {
                setArtwork(Artwork().apply {
                    title = context.getString(R.string.single_default_artwork_title)
                })
            }
        }
        // There's always only one artwork for this provider,
        // so there's never any additional artwork to load
    }

    override fun openArtworkInfo(artwork: Artwork): Boolean {
        val context = context ?: return false
        val uri = ContentUris.withAppendedId(contentUri, artwork.id)
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Could not open artwork info for $uri", e)
            false
        }
    }

    override fun openFile(artwork: Artwork) = FileInputStream(getArtworkFile(
            context ?: throw FileNotFoundException("Invalid Context")))
}
