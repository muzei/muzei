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

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import com.google.android.apps.muzei.util.SelectionBuilder;

import java.util.ArrayList;
import java.util.List;

import static com.google.android.apps.muzei.gallery.GalleryDatabase.ChosenPhotos;
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
}

