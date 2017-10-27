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
import android.arch.persistence.room.Index;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Source information's representation in Room
 */
@Entity(tableName = "sources", indices = @Index(value = "component_name", unique = true))
public class Source {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = BaseColumns._ID)
    public long id;

    @TypeConverters({ComponentNameTypeConverter.class})
    @ColumnInfo(name = "component_name")
    @NonNull
    public final ComponentName componentName;

    public boolean selected;

    public String description;

    @ColumnInfo(name = "network")
    public boolean wantsNetworkAvailable;

    @ColumnInfo(name = "supports_next_artwork")
    public boolean supportsNextArtwork;

    @TypeConverters(UserCommandTypeConverter.class)
    @NonNull
    public List<UserCommand> commands = new ArrayList<>();

    public Source(@NonNull ComponentName componentName) {
        this.componentName = componentName;
    }
}
