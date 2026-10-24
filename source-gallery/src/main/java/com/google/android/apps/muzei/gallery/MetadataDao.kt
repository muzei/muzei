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

package com.google.android.apps.muzei.gallery

import android.net.Uri
import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.ColumnTypeConverters
import com.google.android.apps.muzei.gallery.converter.UriColumnTypeConverter

/**
 * Dao for [Metadata]
 */
@Dao
internal interface MetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: Metadata)

    @ColumnTypeConverters(UriColumnTypeConverter::class)
    @Query("SELECT * FROM metadata_cache WHERE uri = :uri")
    suspend fun metadataForUri(uri: Uri): Metadata?
}
