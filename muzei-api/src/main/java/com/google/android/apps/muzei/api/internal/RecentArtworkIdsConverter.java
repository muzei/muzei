/*
 * Copyright 2018 Google Inc.
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
package com.google.android.apps.muzei.api.internal;

import android.text.TextUtils;

import java.util.ArrayDeque;

import androidx.annotation.NonNull;

/**
 * Converts a ArrayDeque of {@link Long}s into and from a persisted value
 */
public class RecentArtworkIdsConverter {
    @NonNull
    public static ArrayDeque<Long> fromString(String idsString) {
        ArrayDeque<Long> ids = new ArrayDeque<>();
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
        splitter.setString(idsString);
        while (splitter.hasNext()) {
            long id = Long.parseLong(splitter.next());
            // Remove the id if it exists in the list already
            ids.remove(id);
            // Then add it to the end of the list
            ids.add(id);
        }
        return ids;
    }

    @NonNull
    public static String idsListToString(ArrayDeque<Long> ids) {
        return TextUtils.join(",", ids);
    }
}
