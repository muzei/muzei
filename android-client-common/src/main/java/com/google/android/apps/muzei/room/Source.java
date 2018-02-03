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
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;
import android.content.ComponentName;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.room.converter.ComponentNameTypeConverter;
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * Source information's representation in Room
 */
@Entity(tableName = "sources")
public class Source {
    @TypeConverters({ComponentNameTypeConverter.class})
    @ColumnInfo(name = "component_name")
    @PrimaryKey
    @NonNull
    public final ComponentName componentName;

    public boolean selected;

    public String label;

    public String defaultDescription;

    public String description;

    public String getDescription() {
        return !TextUtils.isEmpty(description) ? description : defaultDescription;
    }

    public int color;

    public int targetSdkVersion;

    @TypeConverters({ComponentNameTypeConverter.class})
    public ComponentName settingsActivity;

    @TypeConverters({ComponentNameTypeConverter.class})
    public ComponentName setupActivity;

    public boolean wantsNetworkAvailable;

    public boolean supportsNextArtwork;

    @TypeConverters(UserCommandTypeConverter.class)
    @NonNull
    public List<UserCommand> commands = new ArrayList<>();

    public Source(@NonNull ComponentName componentName) {
        this.componentName = componentName;
    }
}
