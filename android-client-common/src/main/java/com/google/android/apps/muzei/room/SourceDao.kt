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
import kotlinx.coroutines.experimental.CommonPool
import kotlinx.coroutines.experimental.withContext

/**
 * Dao for Sources
 */
@Dao
abstract class SourceDao {

    @get:Query("SELECT * FROM sources")
    abstract val sources: LiveData<List<Source>>

    @get:Query("SELECT * FROM sources")
    abstract val sourcesBlocking: List<Source>

    suspend fun getSources() = withContext(CommonPool) {
        sourcesBlocking
    }

    @get:TypeConverters(ComponentNameTypeConverter::class)
    @get:Query("SELECT component_name FROM sources")
    abstract val sourceComponentNamesBlocking: List<ComponentName>

    @get:Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    abstract val currentSource: LiveData<Source?>

    @get:Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    abstract val currentSourceBlocking: Source?

    suspend fun getCurrentSource() = withContext(CommonPool) {
        currentSourceBlocking
    }

    @get:Query("SELECT * FROM sources WHERE selected=1 AND wantsNetworkAvailable=1")
    internal abstract val currentSourcesThatWantNetworkBlocking: List<Source>

    suspend fun getCurrentSourcesThatWantNetwork() = withContext(CommonPool) {
        currentSourcesThatWantNetworkBlocking
    }

    @Insert
    abstract fun insert(source: Source)

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT component_name FROM sources WHERE component_name LIKE :packageName || '%'")
    abstract fun getSourcesComponentNamesByPackageNameBlocking(packageName: String): List<ComponentName>

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("SELECT * FROM sources WHERE component_name = :componentName")
    abstract fun getSourceByComponentNameBlocking(componentName: ComponentName): Source?

    suspend fun getSourceByComponentName(
            componentName: ComponentName
    ) = withContext(CommonPool) {
        getSourceByComponentNameBlocking(componentName)
    }
    @Update
    abstract fun update(source: Source)

    @Delete
    abstract fun delete(source: Source)

    @TypeConverters(ComponentNameTypeConverter::class)
    @Query("DELETE FROM sources WHERE component_name IN (:componentNames)")
    abstract fun deleteAll(componentNames: Array<ComponentName>)
}
