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

import android.arch.lifecycle.Observer;
import android.arch.persistence.db.SupportSQLiteDatabase;
import android.arch.persistence.room.Database;
import android.arch.persistence.room.Room;
import android.arch.persistence.room.RoomDatabase;
import android.arch.persistence.room.migration.Migration;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteConstraintException;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.apps.muzei.api.MuzeiContract;

/**
 * Room Database for Muzei
 */
@Database(entities = {Artwork.class, Source.class}, version = 5)
public abstract class MuzeiDatabase extends RoomDatabase {
    private static MuzeiDatabase sInstance;

    public abstract SourceDao sourceDao();

    public abstract ArtworkDao artworkDao();

    public static MuzeiDatabase getInstance(Context context) {
        final Context applicationContext = context.getApplicationContext();
        if (sInstance == null) {
            sInstance = Room.databaseBuilder(applicationContext,
                    MuzeiDatabase.class, "muzei.db")
                    .allowMainThreadQueries()
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                    .build();
            sInstance.sourceDao().getCurrentSource().observeForever(
                    new Observer<Source>() {
                        @Override
                        public void onChanged(@Nullable final Source source) {
                            if (source == null) {
                                return;
                            }
                            applicationContext.getContentResolver()
                                    .notifyChange(MuzeiContract.Sources.CONTENT_URI,null);
                            applicationContext.sendBroadcast(
                                    new Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED));
                        }
                    }
            );
        }
        return sInstance;
    }

    private static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull final SupportSQLiteDatabase database) {
            // NO-OP
        }
    };

    private static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull final SupportSQLiteDatabase database) {
            // We can't ALTER TABLE to add a foreign key and we wouldn't know what the FK should be
            // at this point anyways so we'll wipe and recreate the artwork table
            database.execSQL("DROP TABLE " + MuzeiContract.Artwork.TABLE_NAME);
            database.execSQL("CREATE TABLE sources ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "component_name TEXT,"
                    + "selected INTEGER,"
                    + "description TEXT,"
                    + "network INTEGER,"
                    + "supports_next_artwork INTEGER,"
                    + "commands TEXT);");
            database.execSQL("CREATE TABLE artwork ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "sourceComponentName TEXT,"
                    + "imageUri TEXT,"
                    + "title TEXT,"
                    + "byline TEXT,"
                    + "attribution TEXT,"
                    + "token TEXT,"
                    + "metaFont TEXT,"
                    + "date_added INTEGER,"
                    + "viewIntent TEXT,"
                    + " CONSTRAINT fk_source_artwork FOREIGN KEY "
                    + "(sourceComponentName) REFERENCES "
                    + "sources (component_name) ON DELETE CASCADE);");
        }
    };

    private static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull final SupportSQLiteDatabase database) {
            // Handle Sources
            database.execSQL("UPDATE sources "
                    + "SET network = 0 "
                    + "WHERE network IS NULL");
            database.execSQL("UPDATE sources "
                    + "SET supports_next_artwork = 0 "
                    + "WHERE supports_next_artwork IS NULL");
            database.execSQL("UPDATE sources "
                    + "SET commands = \"\" "
                    + "WHERE commands IS NULL");
            database.execSQL("CREATE TABLE sources2 ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "component_name TEXT UNIQUE NOT NULL,"
                    + "selected INTEGER NOT NULL,"
                    + "description TEXT,"
                    + "network INTEGER NOT NULL,"
                    + "supports_next_artwork INTEGER NOT NULL,"
                    + "commands TEXT NOT NULL);");
            try {
                database.execSQL("INSERT INTO sources2 "
                        + "SELECT * FROM sources");
            } catch (SQLiteConstraintException e) {
                // Wtf, multiple sources with the same component_name? Mkay
                // Just move over the component_name and selected flag then
                database.execSQL("INSERT INTO sources2 " +
                        "(component_name, selected, network, supports_next_artwork, commands) "
                        + "SELECT component_name, MAX(selected), "
                        + "0 AS network, 0 AS supports_next_artwork, '' as commands "
                        + "FROM sources GROUP BY component_name");
            }
            database.execSQL("DROP TABLE sources");
            database.execSQL("ALTER TABLE sources2 RENAME TO sources");
            database.execSQL("CREATE UNIQUE INDEX index_sources_component_name "
                    + "ON sources (component_name)");

            // Handle Artwork
            database.execSQL("UPDATE artwork "
                    + "SET metaFont = \"\" "
                    + "WHERE metaFont IS NULL");
            database.execSQL("CREATE TABLE artwork2 ("
                    + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                    + "sourceComponentName TEXT,"
                    + "imageUri TEXT,"
                    + "title TEXT,"
                    + "byline TEXT,"
                    + "attribution TEXT,"
                    + "token TEXT,"
                    + "metaFont TEXT NOT NULL,"
                    + "date_added INTEGER NOT NULL,"
                    + "viewIntent TEXT,"
                    + " CONSTRAINT fk_source_artwork FOREIGN KEY "
                    + "(sourceComponentName) REFERENCES "
                    + "sources (component_name) ON DELETE CASCADE);");
            database.execSQL("INSERT INTO artwork2 "
                    + "SELECT * FROM artwork");
            database.execSQL("DROP TABLE artwork");
            database.execSQL("ALTER TABLE artwork2 RENAME TO artwork");
            database.execSQL("CREATE INDEX index_Artwork_sourceComponentName "
                    + "ON artwork (sourceComponentName)");
        }
    };

    private static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull final SupportSQLiteDatabase database) {
            // NO-OP
        }
    };
}
