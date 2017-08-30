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
import android.arch.persistence.room.Embedded;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.TypeConverters;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.room.converter.UserCommandTypeConverter;

import java.util.ArrayList;
import java.util.List;

/**
 * The result of a join of the Artwork and its Source.
 */
@Entity
public class ArtworkSource {
    @Embedded
    public Artwork artwork;

    @ColumnInfo(name = "supports_next_artwork")
    public boolean supportsNextArtwork;

    @TypeConverters(UserCommandTypeConverter.class)
    @NonNull
    public List<UserCommand> commands = new ArrayList<>();
}
