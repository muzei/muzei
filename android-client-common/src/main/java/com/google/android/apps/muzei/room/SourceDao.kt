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

import android.arch.lifecycle.LiveData
import android.arch.persistence.room.Dao
import android.arch.persistence.room.Delete
import android.arch.persistence.room.Insert
import android.arch.persistence.room.Query
import android.arch.persistence.room.TypeConverters
import android.arch.persistence.room.Update
import android.content.ComponentName

import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter

/**
 * Dao for Sources
 */
@Dao
interface SourceDao {

    @get:Query("SELECT * FROM sources")
    val sources: LiveData<List<Source>>

    @get:Query("SELECT * FROM sources")
    val sourcesBlocking: List<Source>

    @get:TypeConverters(ComponentNameTypeConverter::class)
    @get:Query("SELECT component_name FROM sources")
    val sourceComponentNamesBlocking: List<ComponentName>

    @get:Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    val currentSource: LiveData<Source?>

    @get:Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    val currentSourceBlocking: Source?

    @get:Query("SELECT * FROM sources WHERE selected=1 AND wantsNetworkAvailable=1")
    val currentSourcesThatWantNetworkBlocking: List<Source>

    @Insert
    fun insert(source: Source)

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT component_name FROM sources WHERE component_name LIKE :packageName || '%'")
    fun getSourcesComponentNamesByPackageNameBlocking(packageName: String): List<ComponentName>

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT * FROM sources WHERE component_name = :componentName")
    fun getSourceByComponentNameBlocking(componentName: ComponentName): Source?

    @Update
    fun update(source: Source)

    @Delete
    fun delete(source: Source)

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("DELETE FROM sources WHERE component_name IN (:componentNames)")
    fun deleteAll(componentNames: Array<ComponentName>)
}
