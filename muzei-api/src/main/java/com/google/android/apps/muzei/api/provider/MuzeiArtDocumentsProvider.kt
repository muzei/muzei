/*
 * Copyright 2020 Google Inc.
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

package com.google.android.apps.muzei.api.provider

import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Point
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max

@RequiresApi(Build.VERSION_CODES.KITKAT)
open class MuzeiArtDocumentsProvider : DocumentsProvider() {

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
    }

    private lateinit var providerInfos: Map<String, ProviderInfo>

    override fun onCreate() = true

    @Suppress("DEPRECATION")
    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        providerInfos = info.authority.split(";").asSequence().filter { authority ->
            val authorityEndsWithDocuments = authority.endsWith(".documents")
            if (!authorityEndsWithDocuments) {
                Log.e(TAG, "Authority $authority must end in \".documents\"")
            }
            authorityEndsWithDocuments
        }.map { authority ->
            authority.substringBeforeLast(".documents")
        }.mapNotNull { authority ->
            val pm = context.packageManager
            try {
                pm.resolveContentProvider(authority, PackageManager.GET_DISABLED_COMPONENTS)
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }.also { providerInfo ->
                if (providerInfo == null) {
                    Log.e(TAG, "Could not find MuzeiArtProvider " +
                            "associated with authority $authority")
                }
            }
        }.filter { providerInfo ->
            if (!providerInfo.enabled) {
                if (Log.isLoggable(TAG, Log.INFO)) {
                    Log.i(TAG, "Ignoring ${providerInfo.authority} as it is disabled")
                }
            }
            providerInfo.enabled
        }.map { providerInfo ->
            providerInfo.authority to providerInfo
        }.toList().toMap()
    }

    @SuppressLint("InlinedApi")
    @Throws(FileNotFoundException::class)
    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = context ?: return result
        val pm = context.packageManager
        providerInfos.forEach { (authority, providerInfo) ->
            val title = providerInfo.loadLabel(pm).toString()
            val appName = providerInfo.applicationInfo.loadLabel(pm).toString()
            result.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, authority)
                add(DocumentsContract.Root.COLUMN_ICON, providerInfo.icon)
                add(DocumentsContract.Root.COLUMN_TITLE, title)
                if (title != appName) {
                    add(DocumentsContract.Root.COLUMN_SUMMARY, appName)
                }
                add(DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/png")
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, authority)
            }
        }
        return result
    }

    @Throws(FileNotFoundException::class)
    override fun queryChildDocuments(
            authority: String,
            projection: Array<String>?,
            sortOrder: String?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        val contentUri = ProviderContract.getContentUri(authority)
        val token = Binder.clearCallingIdentity()
        try {
            context.contentResolver.query(contentUri,
                    null, null, null, null, null
            )?.use { data ->
                while (data.moveToNext()) {
                    result.addArtwork(authority, Artwork.fromCursor(data))
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        if (documentId.contains("/")) {
            val (authority, id) = documentId.split("/")
            val contentUri = ProviderContract.getContentUri(authority)
            val uri = ContentUris.withAppendedId(contentUri, id.toLong())

            val token = Binder.clearCallingIdentity()
            try {
                context.contentResolver.query(uri,
                        null, null, null, null
                )?.use { data ->
                    if (data.moveToFirst()) {
                        result.addArtwork(authority, Artwork.fromCursor(data))
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
        } else {
            // This is the root item for the MuzeiArtProvider
            val providerInfo = providerInfos[documentId] ?: return result
            val pm = context.packageManager
            result.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, providerInfo.loadLabel(pm))
                add(DocumentsContract.Document.COLUMN_MIME_TYPE,  DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_DIR_PREFERS_GRID or DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED)
                add(DocumentsContract.Document.COLUMN_SIZE, null)
            }
        }
        return result
    }

    private fun MatrixCursor.addArtwork(authority: String, artwork: Artwork) {
        newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "$authority/${artwork.id}")
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, artwork.title)
            add(DocumentsContract.Document.COLUMN_SUMMARY, artwork.byline)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, "image/png")
            add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
            add(DocumentsContract.Document.COLUMN_SIZE, null)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, artwork.dateModified.time)
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // The only parents are the authorities, the only children are authority/id
        return documentId.startsWith("$parentDocumentId/")
    }

    @Throws(FileNotFoundException::class)
    override fun getDocumentType(documentId: String) = if (documentId.contains("/")) {
        "image/png"
    } else DocumentsContract.Document.MIME_TYPE_DIR

    @Throws(FileNotFoundException::class)
    override fun openDocument(
            documentId: String,
            mode: String,
            signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        val (authority, id) = documentId.split("/")
        val contentUri = ProviderContract.getContentUri(authority)
        val uri = ContentUris.withAppendedId(contentUri, id.toLong())

        val token = Binder.clearCallingIdentity()
        return try {
            context?.contentResolver?.openFileDescriptor(uri, mode, signal)
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
        val (authority, id) = documentId.split("/")
        val tempFile = getThumbnailFile(authority, id)
        if (tempFile.exists() && tempFile.length() != 0L) {
            // We already have a cached thumbnail
            return AssetFileDescriptor(ParcelFileDescriptor.open(tempFile,
                    ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        // We need to generate a new thumbnail
        val contentUri = ProviderContract.getContentUri(authority)
        val uri = ContentUris.withAppendedId(contentUri, id.toLong())
        val token = Binder.clearCallingIdentity()
        val bitmap = try {
            decodeUri(uri,
                    sizeHint.x / 2, sizeHint.y / 2
            ) ?: run {
                throw FileNotFoundException("Unable to generate thumbnail for $uri")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
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
    private fun getThumbnailFile(authority: String, id: String): File {
        val context = context ?: throw FileNotFoundException("Unable to create cache directory")
        val authorityDirectory = File(context.cacheDir, "muzei_$authority")
        val thumbnailDirectory = File(authorityDirectory, "thumbnails")
        if (!thumbnailDirectory.exists() && !thumbnailDirectory.mkdirs()) {
            throw FileNotFoundException("Unable to create thumbnail directory")
        }
        return File(thumbnailDirectory, id)
    }

    private fun decodeUri(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val context = context ?: return null
        val openInputStream = {
            context.contentResolver.openInputStream(uri)
        }
        return try {
            // First we need to get the original width and height of the image
            val (originalWidth, originalHeight) = openInputStream()?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeStream(input, null, options)
                Pair(options.outWidth, options.outHeight)
            } ?: return null.also {
                Log.w(TAG, "Unable to get width and height for $uri")
            }
            // Then we need to get the rotation of the image
            val rotation = try {
                openInputStream()?.use { input ->
                    val exifInterface = ExifInterface(input)
                    when (exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't open EXIF interface for ${toString()}", e)
            } ?: 0
            // Then we need to swap the width and height depending on the rotation
            val width = if (rotation == 90 || rotation == 270) originalHeight else originalWidth
            val height = if (rotation == 90 || rotation == 270) originalWidth else originalHeight
            // And now get the image, sampling it down to the appropriate size if needed
            openInputStream()?.use { input ->
                BitmapFactory.decodeStream(input, null,
                        BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                            if (targetWidth != 0) {
                                inSampleSize = max(
                                        width.sampleSize(targetWidth),
                                        height.sampleSize(targetHeight))
                            }
                        })
            }?.run {
                // Correctly rotate the final, downsampled image
                when (rotation) {
                    0 -> this
                    else -> {
                        val rotateMatrix = Matrix().apply {
                            postRotate(rotation.toFloat())
                        }
                        Bitmap.createBitmap(
                                this, 0, 0,
                                this.width, this.height,
                                rotateMatrix, true).also { rotatedBitmap ->
                            if (rotatedBitmap != this) {
                                recycle()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to get thumbnail for $uri", e)
            null
        }
    }

    private fun Int.sampleSize(targetSize: Int): Int {
        var sampleSize = 1
        while (this / (sampleSize shl 1) > targetSize) {
            sampleSize = sampleSize shl 1
        }
        return sampleSize
    }
}
