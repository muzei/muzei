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

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.ForeignKey;
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.net.Uri;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;
import com.google.android.apps.muzei.room.converter.DateTypeConverter;
import com.google.android.apps.muzei.room.converter.IntentTypeConverter;
import com.google.android.apps.muzei.room.converter.UriTypeConverter;

import java.util.Date;

/**
 * Artwork's representation in Room
 */
@Entity(indices = @Index(value = "sourceComponentName"),
        foreignKeys = @ForeignKey(
                entity = Source.class,
                parentColumns = "component_name",
                childColumns = "sourceComponentName",
                onDelete = ForeignKey.CASCADE))
public class Artwork {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID)
    public long id;

    @TypeConverters(ComponentNameTypeConverter.class)
    public ComponentName sourceComponentName;

    @TypeConverters(UriTypeConverter.class)
    public Uri imageUri;

    public String title;

    public String byline;

    public String attribution;

    public String token;

    @MuzeiContract.Artwork.MetaFontType
    @NonNull
    public String metaFont = MuzeiContract.Artwork.META_FONT_TYPE_DEFAULT;

    @TypeConverters(DateTypeConverter.class)
    @ColumnInfo(name = "date_added")
    @NonNull
    public Date dateAdded = new Date();

    @TypeConverters(IntentTypeConverter.class)
    public Intent viewIntent;

    @NonNull
    public Uri getContentUri() {
        return getContentUri(id);
    }

    public static Uri getContentUri(long id) {
        return ContentUris.appendId(new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(MuzeiContract.AUTHORITY)
                .appendPath("artwork"), id).build();
    }
}