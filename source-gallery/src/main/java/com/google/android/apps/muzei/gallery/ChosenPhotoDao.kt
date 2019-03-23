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

package com.google.android.apps.muzei.gallery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.provider.DocumentsContract
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.paging.DataSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.gallery.BuildConfig.GALLERY_ART_AUTHORITY
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date

/**
 * Dao for [ChosenPhoto]
 */
@Dao
internal abstract class ChosenPhotoDao {

    companion object {
        private const val TAG = "ChosenPhotoDao"
    }

    @get:Query("SELECT * FROM chosen_photos ORDER BY _id DESC")
    internal abstract val chosenPhotosPaged: DataSource.Factory<Int, ChosenPhoto>

    @get:Query("SELECT * FROM chosen_photos ORDER BY _id DESC")
    internal abstract val chosenPhotos: LiveData<List<ChosenPhoto>>

    @get:Query("SELECT * FROM chosen_photos ORDER BY _id DESC")
    internal abstract val chosenPhotosBlocking: List<ChosenPhoto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract suspend fun insertInternal(chosenPhoto: ChosenPhoto): Long

    @Transaction
    open suspend fun insert(
            context: Context,
            chosenPhoto: ChosenPhoto,
            callingApplication: String?
    ): Long = if (persistUriAccess(context, chosenPhoto)) {
        val id = insertInternal(chosenPhoto)
        if (id != 0L && callingApplication != null) {
            val metadata = Metadata(ChosenPhoto.getContentUri(id), Date(),
                    context.getString(R.string.gallery_shared_from, callingApplication))
            GalleryDatabase.getInstance(context).metadataDao().insert(metadata)
        }
        GalleryScanWorker.enqueueInitialScan(listOf(id))
        id
    } else {
        0L
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    internal abstract fun insertAllInternal(chosenPhoto: List<ChosenPhoto>): List<Long>

    fun insertAll(context: Context, uris: Collection<Uri>) {
        insertAllInternal(uris
                .map { ChosenPhoto(it) }
                .filter { persistUriAccess(context, it) }
        ).run {
            GalleryScanWorker.enqueueInitialScan(this)
        }
    }

    private fun persistUriAccess(context: Context, chosenPhoto: ChosenPhoto): Boolean {
        chosenPhoto.isTreeUri = isTreeUri(chosenPhoto.uri)
        if (chosenPhoto.isTreeUri) {
            try {
                context.contentResolver.takePersistableUriPermission(chosenPhoto.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (ignored: SecurityException) {
                // You can't persist URI permissions from your own app, so this fails.
                // We'll still have access to it directly
            }
        } else {
            val haveUriPermission = context.checkUriPermission(chosenPhoto.uri,
                    Binder.getCallingPid(), Binder.getCallingUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
            // If we only have permission to this URI via URI permissions (rather than directly,
            // such as if the URI is from our own app), it is from an external source and we need
            // to make sure to gain persistent access to the URI's content
            if (haveUriPermission) {
                var persistedPermission = false
                // Try to persist access to the URI, saving us from having to store a local copy
                if (DocumentsContract.isDocumentUri(context, chosenPhoto.uri)) {
                    try {
                        context.contentResolver.takePersistableUriPermission(chosenPhoto.uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        persistedPermission = true
                        // If we have a persisted URI permission, we don't need a local copy
                        val cachedFile = GalleryProvider.getCacheFileForUri(context, chosenPhoto.uri)
                        if (cachedFile?.exists() == true) {
                            if (!cachedFile.delete()) {
                                Log.w(TAG, "Unable to delete $cachedFile")
                            }
                        }
                    } catch (ignored: SecurityException) {
                        // If we don't have FLAG_GRANT_PERSISTABLE_URI_PERMISSION (such as when using ACTION_GET_CONTENT),
                        // this will fail. We'll need to make a local copy (handled below)
                    }
                }
                if (!persistedPermission) {
                    // We only need to make a local copy if we weren't able to persist the permission
                    try {
                        writeUriToFile(context, chosenPhoto.uri,
                                GalleryProvider.getCacheFileForUri(context, chosenPhoto.uri))
                    } catch (e: IOException) {
                        Log.e(TAG, "Error downloading gallery image ${chosenPhoto.uri}", e)
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun isTreeUri(possibleTreeUri: Uri): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return DocumentsContract.isTreeUri(possibleTreeUri)
        } else {
            try {
                // Prior to N we can't directly check if the URI is a tree URI, so we have to just try it
                val treeDocumentId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    DocumentsContract.getTreeDocumentId(possibleTreeUri)
                } else {
                    // No tree URIs prior to Lollipop
                    return false
                }
                return treeDocumentId?.isNotEmpty() == true
            } catch (e: IllegalArgumentException) {
                // Definitely not a tree URI
                return false
            }
        }
    }

    @Throws(IOException::class)
    private fun writeUriToFile(context: Context, uri: Uri, destFile: File?) {
        if (destFile == null) {
            throw IOException("Invalid destination for $uri")
        }
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { out ->
                    input.copyTo(out)
                }
            }
        } catch (e: SecurityException) {
            throw IOException("Unable to read Uri: $uri", e)
        } catch (e: UnsupportedOperationException) {
            throw IOException("Unable to read Uri: $uri", e)
        }
    }

    @Query("SELECT * FROM chosen_photos WHERE _id = :id")
    internal abstract fun chosenPhotoBlocking(id: Long): ChosenPhoto?

    @Query("SELECT * FROM chosen_photos WHERE _id = :id")
    abstract suspend fun getChosenPhoto(id: Long): ChosenPhoto?

    @Query("SELECT * FROM chosen_photos WHERE _id IN (:ids)")
    abstract suspend fun getChosenPhotos(ids: List<Long>): List<ChosenPhoto>

    @Query("DELETE FROM chosen_photos WHERE _id IN (:ids)")
    internal abstract suspend fun deleteInternal(ids: List<Long>)

    @Transaction
    open suspend fun delete(context: Context, ids: List<Long>) {
        deleteBackingPhotos(context, getChosenPhotos(ids))
        deleteInternal(ids)
    }

    @Query("DELETE FROM chosen_photos")
    internal abstract suspend fun deleteAllInternal()

    @Transaction
    open suspend fun deleteAll(context: Context) {
        deleteBackingPhotos(context, chosenPhotosBlocking)
        deleteAllInternal()
    }

    /**
     * We can't just simply delete the rows as that won't free up the space occupied by the
     * chosen image files for each row being deleted. Instead we have to query
     * and manually delete each chosen image file
     */
    private suspend fun deleteBackingPhotos(
            context: Context,
            chosenPhotos: List<ChosenPhoto>
    ) = coroutineScope  {
        chosenPhotos.map { chosenPhoto ->
            async {
                val contentUri = ProviderContract.getContentUri(GALLERY_ART_AUTHORITY)
                context.contentResolver.delete(contentUri,
                        "${ProviderContract.Artwork.METADATA}=?",
                        arrayOf(chosenPhoto.uri.toString()))
                val file = GalleryProvider.getCacheFileForUri(context, chosenPhoto.uri)
                if (file?.exists() == true) {
                    if (!file.delete()) {
                        Log.w(TAG, "Unable to delete $file")
                    }
                } else {
                    val uriToRelease = chosenPhoto.uri
                    val contentResolver = context.contentResolver
                    val haveUriPermission = context.checkUriPermission(uriToRelease,
                            Binder.getCallingPid(), Binder.getCallingUid(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
                    if (haveUriPermission) {
                        // Try to release any persisted URI permission for the imageUri
                        val persistedUriPermissions = contentResolver.persistedUriPermissions
                        for (persistedUriPermission in persistedUriPermissions) {
                            if (persistedUriPermission.uri == uriToRelease) {
                                try {
                                    contentResolver.releasePersistableUriPermission(
                                            uriToRelease, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                } catch (e: SecurityException) {
                                    // Thrown if we don't have permission...despite in being in
                                    // the getPersistedUriPermissions(). Alright then.
                                }
                                break
                            }
                        }
                    }
                }
            }
        }.awaitAll()
    }
}
