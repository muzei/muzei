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

package com.google.android.apps.muzei.gallery;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MutableLiveData;
import android.arch.paging.DataSource;
import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.OnConflictStrategy;
import android.arch.persistence.room.Query;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Dao for {@link ChosenPhoto}
 */
@Dao
public abstract class ChosenPhotoDao {
    private static final String TAG = "ChosenPhotoDao";

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract long insertInternal(ChosenPhoto chosenPhoto);

    public LiveData<Long> insert(Context context, final ChosenPhoto chosenPhoto) {
        final MutableLiveData<Long> asyncInsert = new MutableLiveData<>();
        if (persistUriAccess(context, chosenPhoto)) {
            new Thread() {
                @Override
                public void run() {
                    long id = insertInternal(chosenPhoto);
                    asyncInsert.postValue(id);
                }
            }.start();
        } else {
            asyncInsert.setValue(0L);
        }
        return asyncInsert;
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract void insertAllInternal(List<ChosenPhoto> chosenPhoto);

    void insertAll(Context context, Collection<Uri> uris) {
        ArrayList<ChosenPhoto> chosenPhotos = new ArrayList<>();
        for (Uri uri : uris) {
            ChosenPhoto chosenPhoto = new ChosenPhoto(uri);
            if (persistUriAccess(context, chosenPhoto)) {
                chosenPhotos.add(chosenPhoto);
            }
        }
        insertAllInternal(chosenPhotos);
    }

    private boolean persistUriAccess(Context context, ChosenPhoto chosenPhoto) {
        chosenPhoto.isTreeUri = isTreeUri(chosenPhoto.uri);
        if (chosenPhoto.isTreeUri) {
            try {
                context.getContentResolver().takePersistableUriPermission(chosenPhoto.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // You can't persist URI permissions from your own app, so this fails.
                // We'll still have access to it directly
            }
        } else {
            boolean haveUriPermission = context.checkUriPermission(chosenPhoto.uri,
                    Binder.getCallingPid(), Binder.getCallingUid(),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
            // If we only have permission to this URI via URI permissions (rather than directly,
            // such as if the URI is from our own app), it is from an external source and we need
            // to make sure to gain persistent access to the URI's content
            if (haveUriPermission) {
                boolean persistedPermission = false;
                // Try to persist access to the URI, saving us from having to store a local copy
                if (DocumentsContract.isDocumentUri(context, chosenPhoto.uri)) {
                    try {
                        context.getContentResolver().takePersistableUriPermission(chosenPhoto.uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        persistedPermission = true;
                        // If we have a persisted URI permission, we don't need a local copy
                        File cachedFile = GalleryProvider.getCacheFileForUri(context, chosenPhoto.uri);
                        if (cachedFile != null && cachedFile.exists()) {
                            if (!cachedFile.delete()) {
                                Log.w(TAG, "Unable to delete " + cachedFile);
                            }
                        }
                    } catch (SecurityException ignored) {
                        // If we don't have FLAG_GRANT_PERSISTABLE_URI_PERMISSION (such as when using ACTION_GET_CONTENT),
                        // this will fail. We'll need to make a local copy (handled below)
                    }
                }
                if (!persistedPermission) {
                    // We only need to make a local copy if we weren't able to persist the permission
                    try {
                        writeUriToFile(context, chosenPhoto.uri, GalleryProvider.getCacheFileForUri(context, chosenPhoto.uri));
                    } catch (IOException e) {
                        Log.e(TAG, "Error downloading gallery image " + chosenPhoto.uri, e);
                        return false;
                    }
                }
            } else {
                // On API 25 and lower, we don't get URI permissions to URIs
                // from our own package so we manage those URI permissions manually
                ContentResolver resolver = context.getContentResolver();
                try {
                    resolver.call(chosenPhoto.uri, "takePersistableUriPermission",
                            chosenPhoto.uri.toString(), null);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to manually persist uri permissions to " + chosenPhoto.uri, e);
                }
            }
        }
        return true;
    }

    private boolean isTreeUri(Uri possibleTreeUri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return DocumentsContract.isTreeUri(possibleTreeUri);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // Prior to N we can't directly check if the URI is a tree URI, so we have to just try it
            try {
                String treeDocumentId = DocumentsContract.getTreeDocumentId(possibleTreeUri);
                return !TextUtils.isEmpty(treeDocumentId);
            } catch (IllegalArgumentException e) {
                // Definitely not a tree URI
                return false;
            }
        }
        // No tree URIs prior to Lollipop
        return false;
    }

    private static void writeUriToFile(Context context, Uri uri, File destFile) throws IOException {
        if (context == null) {
            return;
        }
        if (destFile == null) {
            throw new IOException("Invalid destination for " + uri);
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = in != null ? new FileOutputStream(destFile) : null) {
            if (in == null) {
                return;
            }
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (SecurityException|UnsupportedOperationException e) {
            throw new IOException("Unable to read Uri: " + uri, e);
        }
    }

    @Query("SELECT * FROM chosen_photos WHERE _id = :id")
    abstract LiveData<ChosenPhoto> getChosenPhoto(Long id);

    @Query("SELECT * FROM chosen_photos WHERE _id = :id")
    abstract ChosenPhoto getChosenPhotoBlocking(Long id);

    @Query("SELECT * FROM chosen_photos ORDER BY _id DESC")
    abstract DataSource.Factory<Integer, ChosenPhoto> getChosenPhotosPaged();

    @Query("SELECT * FROM chosen_photos ORDER BY _id DESC")
    abstract LiveData<List<ChosenPhoto>> getChosenPhotos();

    @Query("SELECT * FROM chosen_photos ORDER BY _id DESC")
    abstract List<ChosenPhoto> getChosenPhotosBlocking();

    @Query("SELECT * FROM chosen_photos WHERE _id IN (:ids)")
    abstract List<ChosenPhoto> getChosenPhotoBlocking(List<Long> ids);

    @Query("DELETE FROM chosen_photos WHERE _id IN (:ids)")
    abstract void deleteInternal(List<Long> ids);

    void delete(Context context, List<Long> ids) {
        deleteBackingPhotos(context, getChosenPhotoBlocking(ids));
        deleteInternal(ids);
    }

    @Query("DELETE FROM chosen_photos")
    abstract void deleteAllInternal();

    void deleteAll(Context context) {
        deleteBackingPhotos(context, getChosenPhotosBlocking());
        deleteAllInternal();
    }

    /**
     * We can't just simply delete the rows as that won't free up the space occupied by the
     * chosen image files for each row being deleted. Instead we have to query
     * and manually delete each chosen image file
     */
    private void deleteBackingPhotos(Context context, List<ChosenPhoto> chosenPhotos) {
        for (ChosenPhoto chosenPhoto : chosenPhotos) {
            File file = GalleryProvider.getCacheFileForUri(context, chosenPhoto.uri);
            if (file != null && file.exists()) {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete " + file);
                }
            } else {
                Uri uriToRelease = chosenPhoto.uri;
                ContentResolver contentResolver = context.getContentResolver();
                boolean haveUriPermission = context.checkUriPermission(uriToRelease,
                        Binder.getCallingPid(), Binder.getCallingUid(),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
                if (haveUriPermission) {
                    // Try to release any persisted URI permission for the imageUri
                    List<UriPermission> persistedUriPermissions = contentResolver.getPersistedUriPermissions();
                    for (UriPermission persistedUriPermission : persistedUriPermissions) {
                        if (persistedUriPermission.getUri().equals(uriToRelease)) {
                            contentResolver.releasePersistableUriPermission(
                                    uriToRelease, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            break;
                        }
                    }
                } else {
                    // On API 25 and lower, we don't get URI permissions to URIs
                    // from our own package so we manage those URI permissions manually
                    try {
                        contentResolver.call(uriToRelease, "releasePersistableUriPermission",
                                uriToRelease.toString(), null);
                    } catch (Exception e) {
                        Log.w(TAG, "Unable to manually release uri permissions to " + chosenPhoto.uri, e);
                    }
                }
            }
        }
    }
}
