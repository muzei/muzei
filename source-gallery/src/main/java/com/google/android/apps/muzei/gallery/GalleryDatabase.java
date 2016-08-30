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
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.BaseColumns;

/**
 * Should be used only via GalleryStore
 */
class GalleryDatabase extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "gallery_source.db";
    private static final int DATABASE_VERSION = 1;

    interface Tables {
        String CHOSEN_PHOTOS = "chosen_photos";
    }

    interface ChosenPhotos extends BaseColumns {
        String URI = "uri";
    }

    public GalleryDatabase(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + Tables.CHOSEN_PHOTOS + " ("
                + ChosenPhotos._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + ChosenPhotos.URI + " TEXT NOT NULL,"
                + "UNIQUE (" + ChosenPhotos.URI + ") ON CONFLICT REPLACE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: proper migrations
        db.execSQL("DROP TABLE IF EXISTS " + Tables.CHOSEN_PHOTOS);
        onCreate(db);
    }
}
