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
import android.content.ContentResolver;
import android.content.ContentUris;
import android.net.Uri;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.gallery.converter.UriTypeConverter;

/**
 * Entity representing a chosen photo in Room
 */
@Entity(tableName = "chosen_photos", indices = @Index(value = "uri", unique = true))
public class ChosenPhoto {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "_id")
    public long id;

    @TypeConverters(UriTypeConverter.class)
    @NonNull
    public Uri uri;

    @ColumnInfo(name = "is_tree_uri")
    boolean isTreeUri;

    public ChosenPhoto(@NonNull Uri uri) {
        this.uri = uri;
    }

    Uri getContentUri() {
        return getContentUri(id);
    }

    public static Uri getContentUri(long id) {
        return ContentUris.appendId(new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(BuildConfig.GALLERY_AUTHORITY), id).build();
    }
}
