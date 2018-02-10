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

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.database.getString
import com.google.android.apps.muzei.api.Artwork
import com.google.android.apps.muzei.api.MuzeiArtSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [MuzeiArtSource] that displays just a single image
 */
class SingleArtSource : MuzeiArtSource("SingleArtSource") {

    companion object {

        private const val TAG = "SingleArtwork"
        private const val ACTION_PUBLISH_NEW_ARTWORK = "publish_new_artwork"
        private const val EXTRA_ARTWORK_TITLE = "title"
        private const val EXTRA_ARTWORK_URI = "uri"

        private val sExecutor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor()
        }

        internal fun getArtworkFile(context: Context): File {
            return File(context.filesDir, "single")
        }

        fun setArtwork(context: Context, artworkUri: Uri): LiveData<Boolean> {
            val mutableLiveData = MutableLiveData<Boolean>()
            sExecutor.submit {
                val tempFile = writeUriToFile(context, artworkUri, getArtworkFile(context)) ?.let { file ->
                    context.startService(Intent(context, SingleArtSource::class.java).apply {
                        action = ACTION_PUBLISH_NEW_ARTWORK
                        putExtra(EXTRA_ARTWORK_TITLE, getDisplayName(context, artworkUri)
                                ?: context.getString(R.string.single_default_artwork_title)
                        )
                        putExtra(EXTRA_ARTWORK_URI, Uri.fromFile(file))
                    })
                }
                mutableLiveData.postValue(tempFile != null)
            }
            return mutableLiveData
        }

        private fun getDisplayName(context: Context, artworkUri: Uri) : String? {
            if (DocumentsContract.isDocumentUri(context, artworkUri)) {
                try {
                    context.contentResolver.query(artworkUri,
                            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                            null, null, null)?.use { data ->
                        if (data.moveToNext()) {
                            return data.getString(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        }
                    }
                } catch (e: SecurityException) {
                    // Whelp, I guess no display name for us
                }
            }
            return null
        }

        private fun writeUriToFile(context: Context, uri: Uri, destFile: File): File? {
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
                            return tempFile
                        }
                    }
                }
                return null
            } catch (e: IOException) {
                Log.e(TAG, "Unable to read Uri: " + uri, e)
                return null
            } catch (e: SecurityException) {
                Log.e(TAG, "Unable to read Uri: " + uri, e)
                return null
            } catch (e: UnsupportedOperationException) {
                Log.e(TAG, "Unable to read Uri: " + uri, e)
                return null
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.takeIf { ACTION_PUBLISH_NEW_ARTWORK == it.action }?.apply {
            publishArtwork(Artwork.Builder()
                    .title(getStringExtra(EXTRA_ARTWORK_TITLE))
                    .imageUri(getParcelableExtra(EXTRA_ARTWORK_URI))
                    .build())
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onUpdate(reason: Int) {}
}
