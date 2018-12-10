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
import android.graphics.Point
import android.net.Uri
import android.os.Binder
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import kotlinx.coroutines.runBlocking
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
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
        runBlocking {
            includeAllArtwork(result, MuzeiDatabase.getInstance(context).artworkDao()
                    .searchArtwork(likeAnyPositionQuery))
        }
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
            runBlocking {
                includeAllArtwork(result, MuzeiDatabase.getInstance(context).artworkDao()
                        .getArtwork())
            }
            result.setNotificationUri(context.contentResolver,
                    DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY,
                            ROOT_DOCUMENT_ID))
        }
        return result
    }

    private suspend fun includeAllArtwork(result: MatrixCursor, artworkList: List<Artwork>) {
        val context = context ?: return
        val currentArtworkId = MuzeiDatabase.getInstance(context).artworkDao()
                .getCurrentArtwork()?.id ?: -1
        for (artwork in artworkList) {
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        artwork.id.toString())
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, artwork.title)
                add(DocumentsContract.Document.COLUMN_SUMMARY, artwork.byline)
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/png")
                // Don't allow deleting the currently displayed artwork
                add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL or
                        (if (artwork.id != currentArtworkId)
                            DocumentsContract.Document.FLAG_SUPPORTS_DELETE else 0))
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
            runBlocking {
                val token = Binder.clearCallingIdentity()
                try {
                    val artwork = MuzeiDatabase.getInstance(context).artworkDao()
                            .getArtworkById(artworkId)
                    if (artwork != null) {
                        includeAllArtwork(result, listOf(artwork))
                    } else {
                            // The artwork isn't there anymore. Delete it to
                            // revoke any document permissions attached to it
                            DocumentsContract.deleteDocument(context.contentResolver,
                                    DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY,
                                            documentId))
                    }
                } finally {
                    Binder.restoreCallingIdentity(token)
                }
            }
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor? {
        val contentResolver = context?.contentResolver ?: return null
        val artworkId = documentId.toLong()
        val token = Binder.clearCallingIdentity()
        try {
            return contentResolver.openFileDescriptor(Artwork.getContentUri(artworkId), mode, signal)
        } catch (e: FileNotFoundException) {
            // The artwork isn't there anymore. Delete it to
            // revoke any document permissions attached to it
            DocumentsContract.deleteDocument(contentResolver,
                    DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY, documentId))
            throw e
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    @Throws(FileNotFoundException::class)
    override fun openDocumentThumbnail(
            documentId: String,
            sizeHint: Point,
            signal: CancellationSignal?
    ): AssetFileDescriptor? {
        val artworkId = documentId.toLong()
        return runBlocking {
            val token = Binder.clearCallingIdentity()
            try {
                openArtworkThumbnail(Artwork.getContentUri(artworkId), sizeHint)
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        }
    }

    @Throws(FileNotFoundException::class)
    private suspend fun openArtworkThumbnail(
            artworkUri: Uri,
            sizeHint: Point
    ): AssetFileDescriptor? {
        val contentResolver = context?.contentResolver ?: return null
        val artworkId = ContentUris.parseId(artworkUri)
        val tempFile = getCacheFileForArtworkUri(artworkId)
        if (tempFile.exists() && tempFile.length() != 0L) {
            // We already have a cached thumbnail
            return AssetFileDescriptor(ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        val bitmap = ImageLoader.decode(
                contentResolver, artworkUri,
                sizeHint.x / 2, sizeHint.y / 2
        ) ?: run {
            // The artwork isn't there anymore. Delete it to
            // revoke any document permissions attached to it
            DocumentsContract.deleteDocument(contentResolver,
                    DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY,
                            artworkId.toString()))
            throw FileNotFoundException("Unable to open artwork for $artworkUri")
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
        val artwork = MuzeiDatabase.getInstance(context).artworkDao()
                .getArtworkById(artworkId)
                ?: throw FileNotFoundException("Unable to get artwork for id $artworkId")
        return File(directory, artwork.id.toString())
    }

    @Throws(FileNotFoundException::class)
    override fun deleteDocument(documentId: String) {
        val context = context ?: return
        val artworkId = documentId.toLong()
        ensureBackground {
            MuzeiDatabase.getInstance(context).artworkDao().deleteById(artworkId)
        }
        context.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(BuildConfig.DOCUMENTS_AUTHORITY,
                        ROOT_DOCUMENT_ID),
                null)
    }

    override fun onCreate(): Boolean {
        return true
    }
}
