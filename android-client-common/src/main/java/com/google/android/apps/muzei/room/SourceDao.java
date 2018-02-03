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

import android.arch.lifecycle.LiveData;
import android.arch.persistence.room.Dao;
import android.arch.persistence.room.Delete;
import android.arch.persistence.room.Insert;
import android.arch.persistence.room.Query;
import android.arch.persistence.room.TypeConverters;
import android.arch.persistence.room.Update;
import android.content.ComponentName;

import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;

import java.util.List;

/**
 * Dao for Sources
 */
@Dao
public interface SourceDao {
    @Insert
    void insert(Source source);

    @Query("SELECT * FROM sources")
    LiveData<List<Source>> getSources();

    @Query("SELECT * FROM sources")
    List<Source> getSourcesBlocking();

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("SELECT component_name FROM sources")
    List<ComponentName> getSourceComponentNamesBlocking();

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("SELECT component_name FROM sources WHERE component_name LIKE :packageName || '%'")
    List<ComponentName> getSourcesComponentNamesByPackageNameBlocking(String packageName);

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("SELECT * FROM sources WHERE component_name = :componentName")
    Source getSourceByComponentNameBlocking(ComponentName componentName);

    @Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    LiveData<Source> getCurrentSource();

    @Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    Source getCurrentSourceBlocking();

    @Query("SELECT * FROM sources WHERE selected=1 AND wantsNetworkAvailable=1")
    LiveData<List<Source>> getCurrentSourcesThatWantNetwork();

    @Update
    void update(Source source);

    @Delete
    void delete(Source source);

    @TypeConverters({ComponentNameTypeConverter.class})
    @Query("DELETE FROM sources WHERE component_name IN (:componentNames)")
    void deleteAll(ComponentName[] componentNames);
}
