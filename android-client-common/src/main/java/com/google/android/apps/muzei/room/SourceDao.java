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

    @Query("SELECT * FROM sources WHERE _id=:id")
    Source getSourceById(long id);

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("SELECT * FROM sources WHERE component_name = :componentName")
    LiveData<Source> getSourceByComponentName(ComponentName componentName);

    @TypeConverters(ComponentNameTypeConverter.class)
    @Query("SELECT * FROM sources WHERE component_name = :componentName")
    Source getSourceByComponentNameBlocking(ComponentName componentName);

    @Query("SELECT _id FROM sources WHERE sources.component_name = " +
            "(SELECT sourceComponentName FROM artwork WHERE artwork._id = :artworkId)")
    long getSourceIdForArtworkId(long artworkId);

    @Query("SELECT * FROM sources")
    LiveData<List<Source>> getSources();

    @Query("SELECT * FROM sources ORDER BY selected DESC, component_name")
    List<Source> getSourcesBlocking();

    @Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    LiveData<Source> getCurrentSource();

    @Query("SELECT * FROM sources WHERE selected=1 ORDER BY component_name")
    Source getCurrentSourceBlocking();

    @Query("SELECT * FROM sources WHERE selected=1 AND network=1")
    LiveData<List<Source>> getCurrentSourcesThatWantNetwork();

    @Update
    void update(Source source);
}
