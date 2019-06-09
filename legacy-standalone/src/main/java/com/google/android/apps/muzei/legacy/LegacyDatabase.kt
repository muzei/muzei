/*
 * Copyright 2019 Google Inc.
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

package com.google.android.apps.muzei.legacy

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room Database for Muzei Legacy Sources
 */
@Database(entities = [(Source::class)], version = 1)
abstract class LegacyDatabase : RoomDatabase() {

    abstract fun sourceDao(): SourceDao

    companion object {
        @Volatile
        private var instance: LegacyDatabase? = null

        fun getInstance(context: Context): LegacyDatabase {
            val applicationContext = context.applicationContext
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(applicationContext,
                        LegacyDatabase::class.java, "legacy.db")
                        .build().also { database ->
                            instance = database
                        }
            }
        }
    }
}
