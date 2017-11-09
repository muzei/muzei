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

package com.google.android.apps.muzei.room;

import android.arch.lifecycle.LiveData;
import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.provider.MuzeiProvider;
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;
import com.google.android.apps.muzei.room.converter.UriTypeConverter;

import java.io.File;
import java.util.List;

/**
 * Dao for Artwork
 */
@Dao
public abstract class ArtworkDao {
    @Insert
    abstract long insertInternal(Artwork artwork);

    public long insert(Context context, Artwork artwork) {
        long id = insertInternal(artwork);
        File artworkFile = MuzeiProvider.getCacheFileForArtworkUri(context, id);
        if (artworkFile != null && artworkFile.exists()) {
            // The image already exists so we'll notify observers to say the new artwork is ready
            // Otherwise, this will be called when the file is written with MuzeiProvider.openFile()
            context.getContentResolver()
                    .notifyChange(MuzeiContract.Artwork.CONTENT_URI, null);
            context.sendBroadcast(
                    new Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED));
            MuzeiProvider.cleanupCachedFiles(context);
        }
        return id;
    }

    @Query("SELECT * FROM artwork ORDER BY date_added DESC")
    public abstract LiveData<List<Artwork>> getArtwork();

    @Query("SELECT * FROM artwork ORDER BY date_added DESC")
    public abstract List<Artwork> getArtworkBlocking();

    @Query("SELECT artwork.* FROM artwork, sources WHERE artwork.sourceComponentName = sources.component_name " +
            "AND sources._id = :sourceId " +
            "ORDER BY date_added DESC")
    public abstract List<Artwork> getArtworkForSourceIdBlocking(long sourceId);

    @Query("SELECT * FROM artwork ORDER BY date_added DESC")
    public abstract LiveData<Artwork> getCurrentArtwork();

    @Query("SELECT * FROM artwork ORDER BY date_added DESC")
    public abstract Artwork getCurrentArtworkBlocking();

    @Query("SELECT * FROM artwork WHERE _id=:id")
    public abstract Artwork getArtworkById(long id);

    @Query("SELECT * FROM artwork WHERE title LIKE :query OR byline LIKE :query OR attribution LIKE :query")
    public abstract List<Artwork> searchArtworkBlocking(String query);

    @Query("SELECT * FROM artwork WHERE token=:token ORDER BY date_added DESC")
    public abstract List<Artwork> getArtworkByToken(String token);

    @TypeConverters(UriTypeConverter.class)
    @Query("SELECT * FROM artwork WHERE imageUri=:imageUri ORDER BY date_added DESC")
    public abstract List<Artwork> getArtworkByImageUri(Uri imageUri);

    @Query("SELECT artwork.*, sources.supports_next_artwork, sources.commands " +
            "FROM artwork, sources " +
            "WHERE artwork.sourceComponentName = " +
            "sources.component_name " +
            "ORDER BY date_added DESC")
    public abstract ArtworkSource getCurrentArtworkWithSourceBlocking();

    @Delete
    abstract void deleteInternal(Artwork artwork);

    public void delete(Context context, Artwork artwork) {
        // Check to see if we can delete the artwork file associated with this row
        boolean canDelete = false;
        if (TextUtils.isEmpty(artwork.token) && artwork.imageUri == null) {
            // An empty image URI and token means the artwork is unique to this specific row
            // so we can always delete it when the associated row is deleted
            canDelete = true;
        } else if (artwork.imageUri == null) {
            // Check to see if the token is unique
            List<Artwork> artworkByToken = getArtworkByToken(artwork.token);
            if (artworkByToken != null && artworkByToken.size() == 1) {
                // There's only one row that uses this token, so we can delete the artwork
                canDelete = true;
            }
        } else {
            // Check to see if the imageUri is unique
            List<Artwork> artworkByImageUri = getArtworkByImageUri(artwork.imageUri);
            if (artworkByImageUri != null && artworkByImageUri.size() == 1) {
                // There's only one row that uses this imageUri, so we can delete the artwork
                canDelete = true;
            }
        }
        if (canDelete) {
            File file = MuzeiProvider.getCacheFileForArtworkUri(context, artwork.id);
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        deleteInternal(artwork);
    }

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("SELECT * FROM artwork WHERE sourceComponentName=:sourceComponentName")
    abstract List<Artwork> getArtworkForSourceBlocking(ComponentName sourceComponentName);

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("DELETE FROM artwork WHERE sourceComponentName = :sourceComponentName " +
            "AND _id NOT IN (:ids)")
    abstract void deleteNonMatchingInternal(ComponentName sourceComponentName, List<Long> ids);

    public void deleteNonMatching(final Context context, final ComponentName sourceComponentName,
            final List<Long> ids) {
        deleteImages(context, sourceComponentName, ids);
        deleteNonMatchingInternal(sourceComponentName, ids);
    }

    @TypeConverters(UriTypeConverter.class)
    @Query("DELETE FROM artwork WHERE imageUri=:imageUri")
    public abstract void deleteByImageUriInternal(Uri imageUri);

    public void deleteByImageUri(Context context, Uri imageUri) {
        List<Artwork> artworkByImageUri = getArtworkByImageUri(imageUri);
        // Assume all artwork with the same imageUri are the same artwork
        // so deleting all of them means we can delete the cached image
        for (Artwork artwork : artworkByImageUri) {
            File file = MuzeiProvider.getCacheFileForArtworkUri(context, artwork.id);
            if (file != null && file.exists()) {
                file.delete();
            }
        }
        deleteByImageUriInternal(imageUri);
    }

    @Query("SELECT * FROM artwork WHERE token=:token AND _id IN (:ids)")
    abstract List<Artwork> findMatchingByToken(String token, List<Long> ids);

    @TypeConverters(UriTypeConverter.class)
    @Query("SELECT * FROM artwork WHERE imageUri=:imageUri AND _id IN (:ids)")
    abstract List<Artwork> findMatchingByImageUri(Uri imageUri, List<Long> ids);

    /**
     * We can't just simply delete the rows as that won't free up the space occupied by the
     * artwork image files associated with each row being deleted. Instead we have to query
     * and manually delete each artwork file
     */
    private void deleteImages(Context context, ComponentName sourceComponentName,
            List<Long> ids) {
        List<Artwork> artworkList = getArtworkForSourceBlocking(sourceComponentName);
        // Now we actually go through the list of rows to be deleted
        // and check if we can delete the artwork image file associated with each one
        for (Artwork artwork : artworkList) {
            if (ids.contains(artwork.id)) {
                // We want to keep this row
                continue;
            }
            boolean canDelete = false;
            if (TextUtils.isEmpty(artwork.token) && artwork.imageUri == null) {
                // An empty image URI and token means the artwork is unique to this specific row
                // so we can always delete it when the associated row is deleted
                canDelete = true;
            } else if (artwork.imageUri == null) {
                // Check to see if all of the artwork by the token is being deleted
                List<Artwork> artworkByToken = findMatchingByToken(artwork.token, ids);
                if (artworkByToken != null && artworkByToken.isEmpty()) {
                    // There's no matching row that uses this token, so it is safe to delete
                    canDelete = true;
                }
            } else {
                // Check to see if all of the artwork by the imageUri is being deleted
                List<Artwork> artworkByImageUri = findMatchingByImageUri(artwork.imageUri, ids);
                if (artworkByImageUri != null && artworkByImageUri.isEmpty()) {
                    // There's no matching row that uses this imageUri, so it is safe to delete
                    canDelete = true;
                }
            }
            if (canDelete) {
                File file = MuzeiProvider.getCacheFileForArtworkUri(context, artwork.id);
                if (file != null && file.exists()) {
                    file.delete();
                }
            }
        }
    }
}
