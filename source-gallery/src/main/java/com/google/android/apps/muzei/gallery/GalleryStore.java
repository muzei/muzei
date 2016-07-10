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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.google.android.apps.muzei.util.SelectionBuilder;

import java.util.ArrayList;
import java.util.List;

import static com.google.android.apps.muzei.gallery.GalleryDatabase.ChosenPhotos;
import static com.google.android.apps.muzei.gallery.GalleryDatabase.MetadataCache;
import static com.google.android.apps.muzei.gallery.GalleryDatabase.Tables;

/**
 * Should be thread-safe.
 * TODO: use a content provider instead.
 */
public class GalleryStore {
    private static GalleryStore sInstance;
    public static GalleryStore getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new GalleryStore(context.getApplicationContext());
        }

        return sInstance;
    }

    private GalleryDatabase mDatabase;

    private GalleryStore(Context applicationContext) {
        mDatabase = new GalleryDatabase(applicationContext);
    }

    public synchronized void setChosenUris(List<Uri> chosenUris) {
        SQLiteDatabase db = mDatabase.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(Tables.CHOSEN_PHOTOS, null, null);
            ContentValues values = new ContentValues();
            for (Uri uri : chosenUris) {
                values.put(MetadataCache.URI, uri.toString());
                db.insertOrThrow(Tables.CHOSEN_PHOTOS, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public synchronized List<Uri> getChosenUris() {
        List<Uri> uris = new ArrayList<>();
        SQLiteDatabase db = mDatabase.getReadableDatabase();
        Cursor cursor = new SelectionBuilder()
                .table(Tables.CHOSEN_PHOTOS)
                .query(db, new String[]{ChosenPhotos.URI}, null);
        if (cursor == null) {
            return uris;
        }

        while (cursor.moveToNext()) {
            uris.add(Uri.parse(cursor.getString(0)));
        }

        cursor.close();
        return uris;
    }

    public synchronized Metadata getCachedMetadata(Uri uri) {
        SQLiteDatabase db = mDatabase.getReadableDatabase();
        Cursor cursor = new SelectionBuilder()
                .table(Tables.METADATA_CACHE)
                .where(MetadataCache.URI + "=?", uri.toString())
                .query(db, new String[]{
                        MetadataCache.DATETIME,
                        MetadataCache.LOCATION,
                        MetadataCache.VERSION,
                }, null);
        if (cursor == null || cursor.getCount() == 0) {
            return null;
        }

        cursor.moveToFirst();
        Metadata metadata = new Metadata();
        metadata.datetime = cursor.getLong(0);
        metadata.location = cursor.getString(1);
        metadata.version = cursor.getInt(2);
        cursor.close();
        return metadata;
    }

    public synchronized void putCachedMetadata(Uri uri, Metadata metadata) {
        SQLiteDatabase db = mDatabase.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put(MetadataCache.URI, uri.toString());
            values.put(MetadataCache.DATETIME, metadata.datetime);
            values.put(MetadataCache.LOCATION, metadata.location);
            values.put(MetadataCache.VERSION, metadata.version);
            db.insertOrThrow(Tables.METADATA_CACHE, null, values);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
            db.close();
        }
    }

    public static class Metadata {
        long datetime;
        String location;
        int version;

        public Metadata() {
        }
    }
}

