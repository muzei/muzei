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

package com.google.android.apps.muzei.gallery;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;

/**
 * Provides access to the Gallery's chosen photos through {@link #openFile(Uri, String)}. Queries,
 * inserts, updates, and deletes are not supported and should instead go through
 * {@link GalleryDatabase}.
 */
public class GalleryProvider extends ContentProvider {
    private static final String TAG = "GalleryProvider";

    @Override
    public int delete(@NonNull final Uri uri, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Deletes are not supported");
    }

    @Override
    public String getType(@NonNull final Uri uri) {
        return "vnd.android.cursor.item/vnd.google.android.apps.muzei.gallery.chosen_photos";
    }

    @Override
    public Uri insert(@NonNull final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException("Inserts are not supported");
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(@NonNull final Uri uri, final String[] projection, final String selection,
                        final String[] selectionArgs, final String sortOrder) {
        throw new UnsupportedOperationException("Queries are not supported");
    }

    @Override
    public ParcelFileDescriptor openFile(@NonNull final Uri uri, @NonNull final String mode) throws FileNotFoundException {
        if (!mode.equals("r")) {
            throw new IllegalArgumentException("Only reading chosen photos is allowed");
        }
        long id = ContentUris.parseId(uri);
        ChosenPhoto chosenPhoto = GalleryDatabase.getInstance(getContext()).chosenPhotoDao().getChosenPhotoBlocking(id);
        if (chosenPhoto == null) {
            throw new FileNotFoundException("Unable to load " + uri);
        }
        final File file = getCacheFileForUri(getContext(), chosenPhoto.uri);
        if ((file == null || !file.exists()) && getContext() != null) {
            // Assume we have persisted URI permission to the imageUri and can read the image directly from the imageUri
            try {
                return getContext().getContentResolver().openFileDescriptor(chosenPhoto.uri, mode);
            } catch (SecurityException|IllegalArgumentException|UnsupportedOperationException
                    |NullPointerException e) {
                Log.d(TAG, "Unable to load " + uri + ", deleting the row", e);
                GalleryDatabase.getInstance(getContext()).chosenPhotoDao().delete(
                        getContext(), Collections.singletonList(chosenPhoto.id));
                throw new FileNotFoundException("No permission to load " + uri);
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    static File getCacheFileForUri(Context context, @NonNull Uri uri) {
        File directory = new File(context.getExternalFilesDir(null), "gallery_images");
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }

        // Create a unique filename based on the imageUri
        StringBuilder filename = new StringBuilder();
        filename.append(uri.getScheme()).append("_")
                .append(uri.getHost()).append("_");
        String encodedPath = uri.getEncodedPath();
        if (!TextUtils.isEmpty(encodedPath)) {
            int length = encodedPath.length();
            if (length > 60) {
                encodedPath = encodedPath.substring(length - 60);
            }
            encodedPath = encodedPath.replace('/', '_');
            filename.append(encodedPath).append("_");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(uri.toString().getBytes("UTF-8"));
            byte[] digest = md.digest();
            for (byte b : digest) {
                if ((0xff & b) < 0x10) {
                    filename.append("0").append(Integer.toHexString((0xFF & b)));
                } else {
                    filename.append(Integer.toHexString(0xFF & b));
                }
            }
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            filename.append(uri.toString().hashCode());
        }

        return new File(directory, filename.toString());
    }

    @Override
    public int update(@NonNull final Uri uri, final ContentValues values, final String selection, final String[] selectionArgs) {
        throw new UnsupportedOperationException("Updates are not supported");
    }
}