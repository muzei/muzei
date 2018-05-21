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

package com.google.android.apps.muzei.room

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Delete
import android.arch.persistence.room.Insert
import android.arch.persistence.room.Query
import android.arch.persistence.room.TypeConverters
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.BaseColumns
import android.util.Log
import androidx.core.database.getLong
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.provider.MuzeiProvider
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter
import com.google.android.apps.muzei.room.converter.UriTypeConverter
import net.nurik.roman.muzei.androidclientcommon.BuildConfig

/**
 * Dao for Artwork
 */
@Dao
abstract class ArtworkDao {

    companion object {
        private const val TAG = "ArtworkDao"
    }

    @get:Query("SELECT * FROM artwork ORDER BY date_added DESC LIMIT 100")
    abstract val artworkBlocking: List<Artwork>

    @get:Query("SELECT * FROM artwork ORDER BY date_added DESC")
    abstract val currentArtwork: LiveData<Artwork?>

    @get:Query("SELECT * FROM artwork ORDER BY date_added DESC")
    abstract val currentArtworkBlocking: Artwork?

    @Insert
    abstract fun insert(artwork: Artwork): Long

    fun insertCompleted(context: Context, id: Long) {
        val artworkFile = MuzeiProvider.getCacheFileForArtworkUri(context, id)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Created artwork $id with cache file $artworkFile")
        }
        if (artworkFile != null && artworkFile.exists()) {
            // The image already exists so we'll notify observers to say the new artwork is ready
            // Otherwise, this will be called when the file is written with MuzeiProvider.openFile()
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Artwork already existed for $id, sending artwork changed broadcast")
            }
            context.contentResolver
                    .notifyChange(MuzeiContract.Artwork.CONTENT_URI, null)
            context.sendBroadcast(
                    Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED))
            MuzeiProvider.cleanupCachedFiles(context)
        }
    }

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT COUNT(distinct imageUri) FROM artwork " + "WHERE sourceComponentName = :sourceComponentName")
    abstract fun getArtworkCountForSourceBlocking(sourceComponentName: ComponentName): Int

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT * FROM artwork WHERE sourceComponentName = :sourceComponentName ORDER BY date_added DESC")
    abstract fun getArtworkForSourceBlocking(sourceComponentName: ComponentName): List<Artwork>

    @Query("SELECT * FROM artwork WHERE _id=:id")
    abstract fun getArtworkById(id: Long): Artwork?

    @Query("SELECT * FROM artwork WHERE title LIKE :query OR byline LIKE :query OR attribution LIKE :query")
    abstract fun searchArtworkBlocking(query: String): List<Artwork>

    @Query("SELECT * FROM artwork WHERE token=:token ORDER BY date_added DESC")
    abstract fun getArtworkByToken(token: String): List<Artwork>?

    @TypeConverters(UriTypeConverter::class)
    @Query("SELECT * FROM artwork WHERE imageUri=:imageUri ORDER BY date_added DESC")
    abstract fun getArtworkByImageUri(imageUri: Uri): List<Artwork>?

    @Delete
    internal abstract fun deleteInternal(artwork: Artwork)

    fun delete(context: Context, artwork: Artwork) {
        // Check to see if we can delete the artwork file associated with this row
        var canDelete = false
        if (artwork.token.isNullOrEmpty() && artwork.imageUri == null) {
            // An empty image URI and token means the artwork is unique to this specific row
            // so we can always delete it when the associated row is deleted
            canDelete = true
        } else if (artwork.imageUri == null) {
            // Check to see if the token is unique
            if (artwork.token?.run { getArtworkByToken(this)?.size == 1 } == true) {
                // There's only one row that uses this token, so we can delete the artwork
                canDelete = true
            }
        } else {
            // Check to see if the imageUri is unique
            if (artwork.imageUri?.run { getArtworkByImageUri(this)?.size == 1 } == true) {
                // There's only one row that uses this imageUri, so we can delete the artwork
                canDelete = true
            }
        }
        if (canDelete) {
            val file = MuzeiProvider.getCacheFileForArtworkUri(context, artwork)
            if (file != null && file.exists()) {
                file.delete()
            }
        }
        deleteInternal(artwork)
    }

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT * FROM artwork WHERE sourceComponentName=:sourceComponentName")
    internal abstract fun getArtworkCursorForSourceBlocking(sourceComponentName: ComponentName): Cursor?

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("DELETE FROM artwork WHERE sourceComponentName = :sourceComponentName " + "AND _id NOT IN (:ids)")
    internal abstract fun deleteNonMatchingInternal(sourceComponentName: ComponentName, ids: List<Long>)

    fun deleteNonMatching(
            context: Context,
            sourceComponentName: ComponentName,
            ids: List<Long>
    ) {
        deleteImages(context, sourceComponentName, ids)
        deleteNonMatchingInternal(sourceComponentName, ids)
    }

    @Query("SELECT * FROM artwork WHERE token=:token AND _id IN (:ids)")
    internal abstract fun findMatchingByToken(token: String, ids: List<Long>): List<Artwork>?

    @TypeConverters(UriTypeConverter::class)
    @Query("SELECT * FROM artwork WHERE imageUri=:imageUri AND _id IN (:ids)")
    internal abstract fun findMatchingByImageUri(imageUri: Uri, ids: List<Long>): List<Artwork>?

    /**
     * We can't just simply delete the rows as that won't free up the space occupied by the
     * artwork image files associated with each row being deleted. Instead we have to query
     * and manually delete each artwork file
     */
    private fun deleteImages(
            context: Context,
            sourceComponentName: ComponentName,
            ids: List<Long>
    ) {
        getArtworkCursorForSourceBlocking(sourceComponentName)?.use { artworkList ->
            // Now we actually go through the list of rows to be deleted
            // and check if we can delete the artwork image file associated with each one
            while (artworkList.moveToNext()) {
                val id = artworkList.getLong(BaseColumns._ID)
                if (ids.contains(id)) {
                    // We want to keep this row
                    continue
                }
                val token = artworkList.getString(artworkList.getColumnIndex("token"))
                val imageUri = artworkList.getString(artworkList.getColumnIndex("imageUri"))
                var canDelete = false
                if (token.isNullOrEmpty() && imageUri.isNullOrEmpty()) {
                    // An empty image URI and token means the artwork is unique to this specific row
                    // so we can always delete it when the associated row is deleted
                    canDelete = true
                } else if (imageUri.isNullOrEmpty()) {
                    // Check to see if all of the artwork by the token is being deleted
                    val artworkByToken = findMatchingByToken(token, ids)
                    if (artworkByToken != null && artworkByToken.isEmpty()) {
                        // There's no matching row that uses this token, so it is safe to delete
                        canDelete = true
                    }
                } else {
                    // Check to see if all of the artwork by the imageUri is being deleted
                    val artworkByImageUri = findMatchingByImageUri(Uri.parse(imageUri), ids)
                    if (artworkByImageUri != null && artworkByImageUri.isEmpty()) {
                        // There's no matching row that uses this imageUri, so it is safe to delete
                        canDelete = true
                    }
                }
                if (canDelete) {
                    val file = MuzeiProvider.getCacheFileForArtworkUri(context, id)
                    if (file != null && file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }
}
