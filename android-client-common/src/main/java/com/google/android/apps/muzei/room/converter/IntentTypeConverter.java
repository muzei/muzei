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

package com.google.android.apps.muzei.room.converter;

import android.arch.persistence.room.TypeConverter;
import android.content.Intent;
import android.util.Log;

import java.net.URISyntaxException;

/**
 * Converts an {@link Intent} into and from a persisted value
 */
public class IntentTypeConverter {
    private final static String TAG = "IntentTypeConverter";

    @TypeConverter
    public static Intent fromString(String uriString) {
        try {
            return uriString == null ? null : Intent.parseUri(uriString, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid Intent: " + uriString, e);
        }
        return null;
    }

    @TypeConverter
    public static String intentToString(Intent intent) {
        return intent == null ? null : intent.toUri(Intent.URI_INTENT_SCHEME);
    }
}
