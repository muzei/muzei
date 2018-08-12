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

import android.arch.persistence.db.SupportSQLiteDatabase
import android.arch.persistence.room.Database
import android.arch.persistence.room.InvalidationTracker
import android.arch.persistence.room.Room
import android.arch.persistence.room.RoomDatabase
import android.arch.persistence.room.migration.Migration
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteConstraintException
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.provider.DirectBootCache
import java.io.File



/**
 * Room Database for Muzei
 */
@Database(entities = [(Artwork::class), (Source::class), (Provider::class)], version = 7)
abstract class MuzeiDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao

    abstract fun providerDao(): ProviderDao

    abstract fun artworkDao(): ArtworkDao

    companion object {
        @Volatile
        private var instance: MuzeiDatabase? = null

        fun getInstance(context: Context): MuzeiDatabase {
            val applicationContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(applicationContext,
                        MuzeiDatabase::class.java, "muzei.db")
                        .addMigrations(
                                MIGRATION_1_2,
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_4_5,
                                MIGRATION_5_6,
                                Migration6to7(applicationContext))
                        .build().also { database ->
                            database.invalidationTracker.addObserver(
                                    object : InvalidationTracker.Observer("artwork") {
                                        override fun onInvalidated(tables: Set<String>) {
                                            DirectBootCache.onArtworkChanged(applicationContext)
                                            applicationContext.contentResolver
                                                    .notifyChange(MuzeiContract.Artwork.CONTENT_URI, null)
                                            applicationContext.sendBroadcast(
                                                    Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED))
                                        }
                                    }
                            )
                            database.invalidationTracker.addObserver(
                                    object : InvalidationTracker.Observer("sources") {
                                        override fun onInvalidated(tables: Set<String>) {
                                            applicationContext.contentResolver
                                                    .notifyChange(MuzeiContract.Sources.CONTENT_URI, null)
                                            applicationContext.sendBroadcast(
                                                    Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED))
                                        }
                                    }
                            )
                            instance = database
                        }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // NO-OP
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // We can't ALTER TABLE to add a foreign key and we wouldn't know what the FK should be
                // at this point anyways so we'll wipe and recreate the artwork table
                database.execSQL("DROP TABLE artwork")
                database.execSQL("CREATE TABLE sources ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "component_name TEXT,"
                        + "selected INTEGER,"
                        + "description TEXT,"
                        + "network INTEGER,"
                        + "supports_next_artwork INTEGER,"
                        + "commands TEXT);")
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
                        + "sources (component_name) ON DELETE CASCADE);")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Handle Sources
                database.execSQL("UPDATE sources "
                        + "SET network = 0 "
                        + "WHERE network IS NULL")
                database.execSQL("UPDATE sources "
                        + "SET supports_next_artwork = 0 "
                        + "WHERE supports_next_artwork IS NULL")
                database.execSQL("UPDATE sources "
                        + "SET commands = \"\" "
                        + "WHERE commands IS NULL")
                database.execSQL("CREATE TABLE sources2 ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "component_name TEXT UNIQUE NOT NULL,"
                        + "selected INTEGER NOT NULL,"
                        + "description TEXT,"
                        + "network INTEGER NOT NULL,"
                        + "supports_next_artwork INTEGER NOT NULL,"
                        + "commands TEXT NOT NULL);")
                try {
                    database.execSQL("INSERT INTO sources2 SELECT * FROM sources")
                } catch (e: SQLiteConstraintException) {
                    // Wtf, multiple sources with the same component_name? Mkay
                    // Just move over the component_name and selected flag then
                    database.execSQL("INSERT INTO sources2 " +
                            "(component_name, selected, network, supports_next_artwork, commands) "
                            + "SELECT component_name, MAX(selected), "
                            + "0 AS network, 0 AS supports_next_artwork, '' as commands "
                            + "FROM sources GROUP BY component_name")
                }

                database.execSQL("DROP TABLE sources")
                database.execSQL("ALTER TABLE sources2 RENAME TO sources")
                database.execSQL("CREATE UNIQUE INDEX index_sources_component_name " + "ON sources (component_name)")

                // Handle Artwork
                database.execSQL("UPDATE artwork "
                        + "SET metaFont = \"\" "
                        + "WHERE metaFont IS NULL")
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
                        + "sources (component_name) ON DELETE CASCADE);")
                database.execSQL("INSERT INTO artwork2 " + "SELECT * FROM artwork")
                database.execSQL("DROP TABLE artwork")
                database.execSQL("ALTER TABLE artwork2 RENAME TO artwork")
                database.execSQL("CREATE INDEX index_Artwork_sourceComponentName " + "ON artwork (sourceComponentName)")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // NO-OP
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Handle Source
                database.execSQL("CREATE TABLE sources2 ("
                        + "component_name TEXT PRIMARY KEY NOT NULL,"
                        + "label TEXT,"
                        + "defaultDescription TEXT,"
                        + "description TEXT,"
                        + "color INTEGER NOT NULL,"
                        + "targetSdkVersion INTEGER NOT NULL,"
                        + "settingsActivity TEXT, "
                        + "setupActivity TEXT,"
                        + "selected INTEGER NOT NULL,"
                        + "wantsNetworkAvailable INTEGER NOT NULL,"
                        + "supportsNextArtwork INTEGER NOT NULL,"
                        + "commands TEXT NOT NULL)")
                database.execSQL("INSERT INTO sources2"
                        + "(component_name, description, color, targetSdkVersion, selected, "
                        + "wantsNetworkAvailable, supportsNextArtwork, commands) "
                        + "SELECT component_name, description, 0, 0, selected, "
                        + "network, supports_next_artwork, commands "
                        + "FROM sources")
                database.execSQL("DROP TABLE sources")
                database.execSQL("ALTER TABLE sources2 RENAME TO sources")
            }
        }

        private class Migration6to7 internal constructor(private val context: Context) : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Handle Provider
                database.execSQL("CREATE TABLE provider ("
                        + "componentName TEXT PRIMARY KEY NOT NULL,"
                        + "supportsNextArtwork INTEGER NOT NULL)")
                // Try to populate the provider table with an initial provider
                // by seeing if the current source has a replacement provider available
                try {
                    database.query("SELECT component_name FROM sources WHERE selected=1").use { selectedSource ->
                        if (selectedSource != null && selectedSource.moveToFirst()) {
                            val componentName = ComponentName.unflattenFromString(
                                    selectedSource.getString(0))
                            val info = context.packageManager.getServiceInfo(componentName,
                                    PackageManager.GET_META_DATA)
                            val metadata = info.metaData
                            if (metadata != null) {
                                val replacement = metadata.getString("replacement")
                                if (replacement != null && replacement.isNotEmpty()) {
                                    ComponentName.unflattenFromString(
                                            "${info.packageName}/$replacement")?.run {
                                        database.execSQL("INSERT INTO provider (componentName) "
                                                + "VALUES (${flattenToShortString()})")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    // Couldn't find the selected source, so there's nothing more to do
                }

                // Handle Artwork
                database.execSQL("DROP TABLE artwork")
                database.execSQL("CREATE TABLE artwork ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "providerComponentName TEXT NOT NULL,"
                        + "imageUri TEXT NOT NULL,"
                        + "title TEXT,"
                        + "byline TEXT,"
                        + "attribution TEXT,"
                        + "metaFont TEXT NOT NULL,"
                        + "date_added INTEGER NOT NULL)")
                database.execSQL("CREATE INDEX index_Artwork_providerComponentName " + "ON artwork (providerComponentName)")

                // Delete previously cached artwork - providers now cache their own artwork
                val artworkDirectory = File(context.filesDir, "artwork")
                artworkDirectory.delete()
            }
        }
    }
}
