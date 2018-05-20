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

package com.google.android.apps.muzei.provider

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.runBlocking
import net.nurik.roman.muzei.androidclientcommon.R
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * DocumentsProvider that allows users to view previous Muzei wallpapers
 */
class MuzeiDocumentsProvider : DocumentsProvider() {

    companion object {
        private const val TAG = "MuzeiDocumentsProvider"
        /**
         * Default root projection
         */
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID)
        /**
         * Default document projection
         */
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SUMMARY,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_FLAGS,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED)

        private const val ROOT_DOCUMENT_ID = "root"
    }

    @SuppressLint("InlinedApi")
    @Throws(FileNotFoundException::class)
    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = context ?: return result
        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "Muzei")
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(DocumentsContract.Root.COLUMN_TITLE, context.getString(R.string.app_name))
            add(DocumentsContract.Root.COLUMN_FLAGS,
                    DocumentsContract.Root.FLAG_LOCAL_ONLY or
                            DocumentsContract.Root.FLAG_SUPPORTS_SEARCH or
                            DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/png")
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun querySearchDocuments(rootId: String, query: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result

        val likeAnyPositionQuery = "%$query%"
        includeAllArtwork(result, runBlocking {
            async {
                MuzeiDatabase.getInstance(context).artworkDao()
                        .searchArtworkBlocking(likeAnyPositionQuery)
            }.await()
        })
        return result
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // The only parent is the Root and it is the parent of everything
        return ROOT_DOCUMENT_ID == parentDocumentId
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String): String {
        return if (ROOT_DOCUMENT_ID == documentId) {
            DocumentsContract.Document.MIME_TYPE_DIR
        } else "image/png"
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<String>?, sortOrder: String?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        if (ROOT_DOCUMENT_ID == parentDocumentId) {
            includeAllArtwork(result, runBlocking {
                async {
                    MuzeiDatabase.getInstance(context).artworkDao()
                            .artworkBlocking
                }.await()
            })
        }
        return result
    }

    private fun includeAllArtwork(result: MatrixCursor, artworkList: List<Artwork>) {
        for (artwork in artworkList) {
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        artwork.id.toString())
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, artwork.title)
                add(DocumentsContract.Document.COLUMN_SUMMARY, artwork.byline)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/png")
                // Don't allow deleting the currently displayed artwork
                add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
                add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, artwork.dateAdded.time)
            }
        }
    }

    @Throws(FileNotFoundException::class)
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        if (ROOT_DOCUMENT_ID == documentId) {
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        context.getString(R.string.app_name))
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
            }
        } else {
            val artworkId = try { documentId.toLong() } catch (e : NumberFormatException) {
                // Documents without a valid artworkId are no longer supported
                // so just return an empty result
                return result
            }
            val artwork = runBlocking {
                async {
                    MuzeiDatabase.getInstance(context).artworkDao()
                            .getArtworkById(artworkId)
                }.await()
            }
            if (artwork != null) {
                includeAllArtwork(result, listOf(artwork))
            }
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? {
        val contentResolver = context?.contentResolver ?: return null
        val artworkId = documentId.toLong()
        return contentResolver.openFileDescriptor(Artwork.getContentUri(artworkId), mode, signal)
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(
            documentId: String,
            sizeHint: Point,
            signal: CancellationSignal?
    ): AssetFileDescriptor? {
        val artworkId = documentId.toLong()
        return runBlocking {
            openArtworkThumbnail(Artwork.getContentUri(artworkId), sizeHint, signal)
        }
    }

    @Throws(FileNotFoundException::class)
    private suspend fun openArtworkThumbnail(
            artworkUri: Uri,
            sizeHint: Point,
            signal: CancellationSignal?
    ): AssetFileDescriptor? {
        val contentResolver = context?.contentResolver ?: return null
        val artworkId = ContentUris.parseId(artworkUri)
        val tempFile = getCacheFileForArtworkUri(artworkId)
        if (tempFile.exists() && tempFile.length() != 0L) {
            // We already have a cached thumbnail
            return AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        val options = BitmapFactory.Options()
        options.inJustDecodeBounds = true
        try {
            contentResolver.openInputStream(artworkUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: IOException) {
            throw FileNotFoundException("Unable to decode artwork")
        }

        if (signal?.isCanceled == true) {
            // Canceled, so we'll stop here to save us the effort of actually decoding the image
            return null
        }
        val targetHeight = 2 * sizeHint.y
        val targetWidth = 2 * sizeHint.x
        val height = options.outHeight
        val width = options.outWidth
        options.inSampleSize = 1
        if (height > targetHeight || width > targetWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / options.inSampleSize > targetHeight || halfWidth / options.inSampleSize > targetWidth) {
                options.inSampleSize *= 2
            }
        }
        options.inJustDecodeBounds = false
        val bitmap = try {
            contentResolver.openInputStream(artworkUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            } ?: throw FileNotFoundException("Unable to open artwork for $artworkUri")
        } catch (e: IOException) {
            throw FileNotFoundException("Unable to decode artwork")
        }

        // Write out the thumbnail to a temporary file
        try {
            FileOutputStream(tempFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing thumbnail", e)
            return null
        }

        return AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                AssetFileDescriptor.UNKNOWN_LENGTH)
    }

    @Throws(FileNotFoundException::class)
    private suspend fun getCacheFileForArtworkUri(artworkId: Long): File {
        val context = context ?: throw FileNotFoundException("Unable to create cache directory")
        val directory = File(context.cacheDir, "artwork_thumbnails")
        if (!directory.exists() && !directory.mkdirs()) {
            throw FileNotFoundException("Unable to create cache directory")
        }
        val artwork = async {
            MuzeiDatabase.getInstance(context).artworkDao()
                    .getArtworkById(artworkId)
        }.await() ?: throw FileNotFoundException("Unable to get artwork for id $artworkId")
        return File(directory, artwork.id.toString())
    }

    override fun onCreate(): Boolean {
        return true
    }
}
