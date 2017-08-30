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

package com.google.android.apps.muzei.gallery;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.net.Uri;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.google.android.apps.muzei.gallery.converter.DateTypeConverter;
import com.google.android.apps.muzei.gallery.converter.UriTypeConverter;

import java.util.Date;

/**
 * Entity representing a row of cached metadata in Room
 */
@Entity(tableName = "metadata_cache", indices = @Index(value = "uri", unique = true))
class Metadata {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    public long id;

    @TypeConverters(UriTypeConverter.class)
    @NonNull
    public Uri uri;

    @TypeConverters(DateTypeConverter.class)
    @ColumnInfo(name = "datetime")
    @Nullable
    Date date;

    @Nullable
    String location;

    Metadata(@NonNull Uri uri) {
        this.uri = uri;
    }
}
