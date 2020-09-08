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

/**
 * An implementation of [DocumentsProvider] that provides users direct access to the
 * images of one or more [MuzeiArtProvider] instances.
 *
 * ### Linking a [MuzeiArtProvider] to [MuzeiArtDocumentsProvider]
 *
 * Each [MuzeiArtProvider] has an authority associated with it, which uniquely
 * defines it across all apps. This means it should generally be namespaced similar to
 * your application ID - i.e., `com.example.artprovider`.
 *
 * A [MuzeiArtDocumentsProvider] uses the `android:authorities` assigned to it as the mechanism
 * for linking it to a single [MuzeiArtProvider] instances from your app - the
 * authority used **must** be that of a valid [MuzeiArtProvider] plus the suffix
 * `.documents`. For example, if your [MuzeiArtProvider] had the authority of
 * `com.example.artprovider`, you would use an authority of `com.example.artprovider.documents`:
 *
 * ```
 * <provider android:name="com.google.android.apps.muzei.api.provider.MuzeiArtDocumentsProvider"
 *   android:authorities="com.example.artprovider.documents"
 *   android:exported="true"
 *   android:grantUriPermissions="true"
 *   android:permission="android.permission.MANAGE_DOCUMENTS">
 *   <intent-filter>
 *       <action android:name="android.content.action.DOCUMENTS_PROVIDER" />
 *   </intent-filter>
 * </provider>
 * ```
 *
 * The [MuzeiArtDocumentsProvider] will automatically make the artwork from the
 * [MuzeiArtProvider] available via the system file picker and Files app.
 *
 * ### Subclassing [MuzeiArtDocumentsProvider]
 *
 * Android enforces that a single `android:name` can only be present at most once in the manifest.
 * While in most cases this is not an issue, it does mean that only a single
 * [MuzeiArtDocumentsProvider] class can be added to the final merged application manifest.
 *
 * Therefore in cases where you do not control the final application manifest (e.g., when
 * providing a [MuzeiArtProvider] and [MuzeiArtDocumentsProvider] pair as part of a library),
 * it is strongly recommended to subclass [MuzeiArtDocumentsProvider], ensuring that the
 * `android:name` in the manifest is unique.
 *
 * ```
 * class MyArtDocumentsProvider : MuzeiArtDocumentsProvider()
 * ```
 *
 * It is not necessary to override any methods in [MuzeiArtDocumentsProvider].
 *
 * ### Supporting multiple [MuzeiArtProvider] instances in your app
 *
 * Each [MuzeiArtDocumentsProvider] is associated with a single [MuzeiArtProvider] via the
 * `android:authorities` attribute. To support multiple [MuzeiArtProvider] instances in your
 * app, you must subclass [MuzeiArtDocumentsProvider] (as described above) and add each
 * separate instance to your manifest, each with the appropriate authority.
 *
 * @constructor Constructs a `MuzeiArtDocumentsProvider`.
 */
@RequiresApi(Build.VERSION_CODES.KITKAT)
public open class MuzeiArtDocumentsProvider : DocumentsProvider() {

    public companion object {
        private const val TAG = "MuzeiArtDocProvider"
        /**
         * Default root projection
         */
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_SUMMARY,
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

    /**
     * @suppress
     */
    final override fun onCreate(): Boolean = true

    /**
     * @suppress
     */
    @Suppress("DEPRECATION")
    final override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        val authorities = info.authority.split(";")
        if (authorities.size > 1) {
            Log.w(TAG, "There are known issues with OEMs not supporting multiple " +
                    "authorities in a single DocumentsProvider. It is recommended to subclass " +
                    "MuzeiArtDocumentsProvider and use a single authority for each. " +
                    "Received $authorities")
        }
        providerInfos = authorities.asSequence().filter { authority ->
            val authorityEndsWithDocuments = authority.endsWith(".documents")
            if (!authorityEndsWithDocuments) {
                Log.e(TAG, "Authority $authority must end in \".documents\"")
            }
            authorityEndsWithDocuments
        }.map { authority ->
            authority.substringBeforeLast(".documents")
        }.mapNotNull { authority ->
            val pm = context.packageManager
            pm.resolveContentProvider(authority, PackageManager.GET_DISABLED_COMPONENTS).also { providerInfo ->
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

    /**
     * @suppress
     */
    @SuppressLint("InlinedApi")
    @Throws(FileNotFoundException::class)
    final override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val context = context ?: return result
        val pm = context.packageManager
        providerInfos.forEach { (authority, providerInfo) ->
            val title = providerInfo.loadLabel(pm).toString()
            val appName = providerInfo.applicationInfo.loadLabel(pm).toString()
            val providerIcon = providerInfo.icon
            val appIcon = providerInfo.applicationInfo.icon
            result.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, authority)
                add(DocumentsContract.Root.COLUMN_ICON,
                        if (providerIcon != 0) providerIcon else appIcon)
                if (title.isNotBlank()) {
                    add(DocumentsContract.Root.COLUMN_TITLE, title)
                    if (title != appName) {
                        add(DocumentsContract.Root.COLUMN_SUMMARY, appName)
                    }
                } else {
                    add(DocumentsContract.Root.COLUMN_TITLE, appName)
                }
                add(DocumentsContract.Root.COLUMN_FLAGS,
                        DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                                DocumentsContract.Root.FLAG_SUPPORTS_RECENTS)
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "image/png")
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, authority)
            }
        }
        return result
    }

    /**
     * @suppress
     */
    final override fun queryRecentDocuments(
            authority: String,
            projection: Array<String>?
    ): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val context = context ?: return result
        val contentUri = ProviderContract.getContentUri(authority)
        val token = Binder.clearCallingIdentity()
        try {
            context.contentResolver.query(contentUri,
                    null, null, null,
                    "${ProviderContract.Artwork.DATE_MODIFIED} DESC",
                    null
            )?.use { data ->
                while (data.moveToNext() && result.count < 64) {
                    result.addArtwork(authority, Artwork.fromCursor(data))
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        result.setNotificationUri(context.contentResolver,
                DocumentsContract.buildRecentDocumentsUri(authority, authority))
        return result
    }

    /**
     * @suppress
     */
    @Throws(FileNotFoundException::class)
    final override fun queryChildDocuments(
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
        result.setNotificationUri(context.contentResolver,
            DocumentsContract.buildChildDocumentsUri(authority, authority))
        return result
    }

    /**
     * @suppress
     */
    @Throws(FileNotFoundException::class)
    final override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
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
            result.setNotificationUri(context.contentResolver,
                    DocumentsContract.buildDocumentUri(authority, documentId))
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
            result.setNotificationUri(context.contentResolver,
                    DocumentsContract.buildDocumentUri(documentId, documentId))
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

    /**
     * @suppress
     */
    final override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // The only parents are the authorities, the only children are authority/id
        return documentId.startsWith("$parentDocumentId/")
    }

    /**
     * @suppress
     */
    @Throws(FileNotFoundException::class)
    final override fun getDocumentType(
            documentId: String
    ): String = if (documentId.contains("/")) {
        "image/png"
    } else DocumentsContract.Document.MIME_TYPE_DIR

    /**
     * @suppress
     */
    @Throws(FileNotFoundException::class)
    final override fun openDocument(
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

    /**
     * @suppress
     */
    @Throws(FileNotFoundException::class)
    final override fun openDocumentThumbnail(
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
