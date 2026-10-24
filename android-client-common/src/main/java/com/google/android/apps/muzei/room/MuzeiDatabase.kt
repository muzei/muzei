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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteConstraintException
import androidx.room3.AutoMigration
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.provider.DirectBootCache
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.io.File

/**
 * Room Database for Muzei
 */
@Database(
    entities = [(Artwork::class), (Provider::class)],
    autoMigrations = [
        AutoMigration(from = 4, to = 5)
    ],
    version = 9
)
abstract class MuzeiDatabase : RoomDatabase() {

    abstract fun providerDao(): ProviderDao

    abstract fun artworkDao(): ArtworkDao

    companion object {
        @Volatile
        private var instance: MuzeiDatabase? = null

        @OptIn(DelicateCoroutinesApi::class)
        fun getInstance(context: Context): MuzeiDatabase {
            val applicationContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(applicationContext,
                        MuzeiDatabase::class.java, "muzei.db")
                        .addMigrations(
                                MIGRATION_1_2,
                                MIGRATION_2_3,
                                MIGRATION_3_4,
                                MIGRATION_5_6,
                                Migration6to8(applicationContext),
                                Migration7to8(applicationContext),
                                MIGRATION_8_9)
                        .build().also { database ->
                            @Suppress("DEPRECATION")
                            database.invalidationTracker.createFlow("artwork").onEach {
                                DirectBootCache.onArtworkChanged(applicationContext)
                                applicationContext.contentResolver
                                    .notifyChange(MuzeiContract.Artwork.CONTENT_URI, null)
                                applicationContext.sendBroadcast(
                                    Intent(MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED)
                                )
                            }.launchIn(GlobalScope)
                            @Suppress("DEPRECATION")
                            database.invalidationTracker.createFlow("provider").onEach {
                                applicationContext.contentResolver
                                    .notifyChange(MuzeiContract.Sources.CONTENT_URI, null)
                                // First send a targeted broadcast just to ourselves
                                applicationContext.sendBroadcast(
                                    Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED).apply {
                                        `package` = applicationContext.packageName
                                    })
                                // Now send another broadcast to other apps listening
                                // (it is expected that our own listener filters
                                // these second calls out)
                                applicationContext.sendBroadcast(
                                    Intent(MuzeiContract.Sources.ACTION_SOURCE_CHANGED)
                                )
                            }.launchIn(GlobalScope)
                            instance = database
                        }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // NO-OP
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // We can't ALTER TABLE to add a foreign key and we wouldn't know what the FK should be
                // at this point anyways so we'll wipe and recreate the artwork table
                connection.execSQL("DROP TABLE artwork")
                connection.execSQL("CREATE TABLE sources ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "component_name TEXT,"
                        + "selected INTEGER,"
                        + "description TEXT,"
                        + "network INTEGER,"
                        + "supports_next_artwork INTEGER,"
                        + "commands TEXT);")
                connection.execSQL("CREATE TABLE artwork ("
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
            override suspend fun migrate(connection: SQLiteConnection) {
                // Handle Sources
                connection.execSQL("UPDATE sources "
                        + "SET network = 0 "
                        + "WHERE network IS NULL")
                connection.execSQL("UPDATE sources "
                        + "SET supports_next_artwork = 0 "
                        + "WHERE supports_next_artwork IS NULL")
                connection.execSQL("UPDATE sources "
                        + "SET commands = \"\" "
                        + "WHERE commands IS NULL")
                connection.execSQL("CREATE TABLE sources2 ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "component_name TEXT UNIQUE NOT NULL,"
                        + "selected INTEGER NOT NULL,"
                        + "description TEXT,"
                        + "network INTEGER NOT NULL,"
                        + "supports_next_artwork INTEGER NOT NULL,"
                        + "commands TEXT NOT NULL);")
                try {
                    connection.execSQL("INSERT INTO sources2 SELECT * FROM sources")
                } catch (_: SQLiteConstraintException) {
                    // Wtf, multiple sources with the same component_name? Mkay
                    // Just move over the component_name and selected flag then
                    connection.execSQL("INSERT INTO sources2 " +
                            "(component_name, selected, network, supports_next_artwork, commands) "
                            + "SELECT component_name, MAX(selected), "
                            + "0 AS network, 0 AS supports_next_artwork, '' as commands "
                            + "FROM sources GROUP BY component_name")
                }

                connection.execSQL("DROP TABLE sources")
                connection.execSQL("ALTER TABLE sources2 RENAME TO sources")
                connection.execSQL("CREATE UNIQUE INDEX index_sources_component_name ON sources (component_name)")

                // Handle Artwork
                connection.execSQL("UPDATE artwork "
                        + "SET metaFont = \"\" "
                        + "WHERE metaFont IS NULL")
                connection.execSQL("CREATE TABLE artwork2 ("
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
                connection.execSQL("INSERT INTO artwork2 SELECT * FROM artwork")
                connection.execSQL("DROP TABLE artwork")
                connection.execSQL("ALTER TABLE artwork2 RENAME TO artwork")
                connection.execSQL("CREATE INDEX index_Artwork_sourceComponentName ON artwork (sourceComponentName)")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Handle Source
                connection.execSQL("CREATE TABLE sources2 ("
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
                connection.execSQL("INSERT INTO sources2"
                        + "(component_name, description, color, targetSdkVersion, selected, "
                        + "wantsNetworkAvailable, supportsNextArtwork, commands) "
                        + "SELECT component_name, description, 0, 0, selected, "
                        + "network, supports_next_artwork, commands "
                        + "FROM sources")
                connection.execSQL("DROP TABLE sources")
                connection.execSQL("ALTER TABLE sources2 RENAME TO sources")
            }
        }

        /**
         * Skip directly from version 6 to 8, avoiding the intermediate database version
         * 7 which used the provider's ComponentName as the key.
         */
        private class Migration6to8(private val context: Context) : Migration(6, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Handle Provider
                connection.execSQL("CREATE TABLE provider ("
                        + "authority TEXT PRIMARY KEY NOT NULL,"
                        + "supportsNextArtwork INTEGER NOT NULL)")
                // Try to populate the provider table with an initial provider
                // by seeing if the current source has a replacement provider available
                try {
                    connection.prepare("SELECT component_name FROM sources WHERE selected=1").use { selectedSource ->
                        if (selectedSource.step()) {
                            val componentName = ComponentName.unflattenFromString(
                                selectedSource.getText(0)
                            )!!
                            @Suppress("DEPRECATION")
                            val info = context.packageManager.getServiceInfo(componentName,
                                    PackageManager.GET_META_DATA)
                            val metadata = info.metaData
                            if (metadata != null) {
                                val replacement = metadata.getString("replacement")
                                if (!replacement.isNullOrEmpty()) {
                                    @Suppress("DEPRECATION")
                                    val providerInfo = context.packageManager
                                            .resolveContentProvider(replacement, 0) ?: run {
                                        ComponentName.unflattenFromString(
                                                "${info.packageName}/$replacement")?.run {
                                            context.packageManager
                                                    .getProviderInfo(this, 0)
                                        }
                                    }
                                    if (providerInfo != null) {
                                        connection.prepare("INSERT INTO provider " +
                                                "(authority, supportsNextArtwork) "
                                                    + "VALUES (?, ?)"
                                        ).use { insertStmt ->
                                            insertStmt.bindText(1, providerInfo.authority!!)
                                            insertStmt.bindBoolean(2, false)
                                            insertStmt.step()
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    // Couldn't find the selected source, so there's nothing more to do
                }

                // Handle Artwork
                connection.execSQL("DROP TABLE artwork")
                connection.execSQL("CREATE TABLE artwork ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "providerAuthority TEXT NOT NULL,"
                        + "imageUri TEXT NOT NULL,"
                        + "title TEXT,"
                        + "byline TEXT,"
                        + "attribution TEXT,"
                        + "metaFont TEXT NOT NULL,"
                        + "date_added INTEGER NOT NULL)")
                connection.execSQL("CREATE INDEX index_Artwork_providerAuthority ON artwork (providerAuthority)")

                // Delete previously cached artwork - providers now cache their own artwork
                val artworkDirectory = File(context.filesDir, "artwork")
                artworkDirectory.delete()
            }
        }

        /**
         * Handle the migration from the intermediate database version 7, which
         * used the ComponentName of the provider as the unique key
         */
        private class Migration7to8(private val context: Context) :
                Migration(7, 8) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Handle Provider
                connection.execSQL("CREATE TABLE provider2 ("
                        + "authority TEXT PRIMARY KEY NOT NULL,"
                        + "supportsNextArtwork INTEGER NOT NULL)")
                try {
                    connection.prepare("SELECT componentName, supportsNextArtwork FROM provider").use {
                        selectedProvider ->
                        if (selectedProvider.step()) {
                            val componentName = ComponentName.unflattenFromString(
                                selectedProvider.getText(0)
                            )!!
                            val supportsNextArtwork = selectedProvider.getBoolean(1)
                            @Suppress("DEPRECATION")
                            val info = context.packageManager.getProviderInfo(componentName, 0)
                            connection.prepare(
                                "INSERT INTO provider2 " +
                                "(authority, supportsNextArtwork) "
                                        + "VALUES (?, ?)"
                            ).use { insertStmt ->
                                insertStmt.bindText(1, info.authority!!)
                                insertStmt.bindBoolean(2, supportsNextArtwork)
                                insertStmt.step()
                            }
                        }
                    }
                } catch (_: PackageManager.NameNotFoundException) {
                    // Couldn't find the selected provider, so there's nothing more to do
                }
                connection.execSQL("DROP TABLE provider")
                connection.execSQL("ALTER TABLE provider2 RENAME TO provider")

                // Handle Artwork
                connection.execSQL("CREATE TABLE artwork2 ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                        + "providerAuthority TEXT NOT NULL,"
                        + "imageUri TEXT NOT NULL,"
                        + "title TEXT,"
                        + "byline TEXT,"
                        + "attribution TEXT,"
                        + "metaFont TEXT NOT NULL,"
                        + "date_added INTEGER NOT NULL)")
                connection.prepare("SELECT _id, providerComponentName, imageUri, " +
                            "title, byline, attribution, metaFont, date_added FROM artwork").use { artwork ->
                    while (artwork.step()) {
                        val id = artwork.getLong(0)
                        val componentName = ComponentName.unflattenFromString(
                            artwork.getText(1)
                        )!!
                        val imageUri = artwork.getText(2)
                        val title = if (artwork.isNull(3)) null else artwork.getText(3)
                        val byline = if (artwork.isNull(4)) null else artwork.getText(4)
                        val attribution = if (artwork.isNull(5)) null else artwork.getText(5)
                        val metaFont = artwork.getText(6)
                        val dateAdded = artwork.getLong(7)

                        try {
                            @Suppress("DEPRECATION")
                            val info = context.packageManager.getProviderInfo(componentName, 0)
                            connection.prepare("INSERT INTO artwork2 " +
                                    "(_id, providerAuthority, imageUri, " +
                                    "title, byline, attribution, metaFont, date_added) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use { insertStmt ->
                                insertStmt.bindLong(1, id)
                                insertStmt.bindText(2, info.authority!!)
                                insertStmt.bindText(3, imageUri)
                                if (title != null) {
                                    insertStmt.bindText(4, title)
                                } else {
                                    insertStmt.bindNull(4)
                                }
                                if (byline != null) {
                                    insertStmt.bindText(5, byline)
                                } else {
                                    insertStmt.bindNull(5)
                                }
                                if (attribution != null) {
                                    insertStmt.bindText(6, attribution)
                                } else {
                                    insertStmt.bindNull(6)
                                }
                                insertStmt.bindText(7, metaFont)
                                insertStmt.bindLong(8, dateAdded)
                                insertStmt.step()
                            }
                        } catch (_: PackageManager.NameNotFoundException) {
                            // Couldn't find the provider, so there's nothing more to do
                        }
                    }
                }
                connection.execSQL("DROP TABLE artwork")
                connection.execSQL("ALTER TABLE artwork2 RENAME TO artwork")
                connection.execSQL("CREATE INDEX index_Artwork_providerAuthority ON artwork (providerAuthority)")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override suspend fun migrate(connection: SQLiteConnection) {
                // Drop the legacy source table
                connection.execSQL("DROP TABLE sources")
            }
        }
    }
}