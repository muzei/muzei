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

package com.google.android.apps.muzei.gallery

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.UnsupportedEncodingException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Provides access to the Gallery's chosen photos through [.openFile]. Queries,
 * inserts, updates, and deletes are not supported and should instead go through
 * [GalleryDatabase].
 */
class GalleryProvider : ContentProvider() {

    companion object {
        private const val TAG = "GalleryProvider"

        internal fun getCacheFileForUri(context: Context, uri: Uri): File? {
            val directory = File(context.getExternalFilesDir(null), "gallery_images")
            if (!directory.exists() && !directory.mkdirs()) {
                return null
            }

            // Create a unique filename based on the imageUri
            val filename = StringBuilder()
            filename.append(uri.scheme).append("_")
                    .append(uri.host).append("_")
            val encodedPath = uri.encodedPath.takeUnless { it.isNullOrEmpty() }?.run {
                if (length > 60) {
                    substring(length - 60)
                } else {
                    this
                }.replace('/', '_')
            }
            if (encodedPath != null) {
                filename.append(encodedPath).append("_")
            }
            try {
                val md = MessageDigest.getInstance("MD5")
                md.update(uri.toString().toByteArray(charset("UTF-8")))
                val digest = md.digest()
                filename.append(digest.joinToString(separator = "") {
                    it.toInt().and(0xff).toString(16).padStart(2, '0')
                })
            } catch (e: NoSuchAlgorithmException) {
                filename.append(uri.toString().hashCode())
            } catch (e: UnsupportedEncodingException) {
                filename.append(uri.toString().hashCode())
            }

            return File(directory, filename.toString())
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Deletes are not supported")
    }

    override fun getType(uri: Uri): String? {
        return "vnd.android.cursor.item/vnd.google.android.apps.muzei.gallery.chosen_photos"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("Inserts are not supported")
    }

    override fun onCreate(): Boolean {
        return true
    }

    override fun query(
            uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?
    ): Cursor? {
        throw UnsupportedOperationException("Queries are not supported")
    }

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context: Context = context ?: return null
        if (mode != "r") {
            throw IllegalArgumentException("Only reading chosen photos is allowed")
        }
        val id = ContentUris.parseId(uri)
        val chosenPhoto = GalleryDatabase.getInstance(context).chosenPhotoDao()
                .chosenPhotoBlocking(id) ?: throw FileNotFoundException("Unable to load $uri")
        val file = getCacheFileForUri(context, chosenPhoto.uri)
        if (file == null || !file.exists()) {
            // Assume we have persisted URI permission to the imageUri and can read the image directly from the imageUri
            try {
                return context.contentResolver.openFileDescriptor(chosenPhoto.uri, mode)
            } catch (e: SecurityException) {
                Log.d(TAG, "Unable to load $uri, deleting the row", e)
                GlobalScope.launch {
                    GalleryDatabase.getInstance(context).chosenPhotoDao()
                            .delete(context, listOf(chosenPhoto.id))
                }
                throw FileNotFoundException("No permission to load $uri")
            } catch (e: IllegalArgumentException) {
                Log.d(TAG, "Unable to load $uri, deleting the row", e)
                GlobalScope.launch {
                    GalleryDatabase.getInstance(context).chosenPhotoDao()
                            .delete(context, listOf(chosenPhoto.id))
                }
                throw FileNotFoundException("No permission to load $uri")
            } catch (e: UnsupportedOperationException) {
                Log.d(TAG, "Unable to load $uri, deleting the row", e)
                GlobalScope.launch {
                    GalleryDatabase.getInstance(context).chosenPhotoDao()
                            .delete(context, listOf(chosenPhoto.id))
                }
                throw FileNotFoundException("No permission to load $uri")
            } catch (e: NullPointerException) {
                Log.d(TAG, "Unable to load $uri, deleting the row", e)
                GlobalScope.launch {
                    GalleryDatabase.getInstance(context).chosenPhotoDao()
                            .delete(context, listOf(chosenPhoto.id))
                }
                throw FileNotFoundException("No permission to load $uri")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int {
        throw UnsupportedOperationException("Updates are not supported")
    }
}