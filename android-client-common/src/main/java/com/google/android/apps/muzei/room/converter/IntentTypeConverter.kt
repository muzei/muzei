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

package com.google.android.apps.muzei.room.converter

import android.arch.persistence.room.TypeConverter
import android.content.Intent
import android.util.Log

import java.net.URISyntaxException

/**
 * Converts an [Intent] into and from a persisted value
 */
class IntentTypeConverter {
    companion object {
        private const val TAG = "IntentTypeConverter"
    }

    @TypeConverter
    fun fromString(uriString: String?): Intent? {
        try {
            return if (uriString == null) null else Intent.parseUri(uriString, Intent.URI_INTENT_SCHEME)
        } catch (e: URISyntaxException) {
            Log.e(TAG, "Invalid Intent: $uriString", e)
        }

        return null
    }

    @TypeConverter
    fun intentToString(intent: Intent?): String? {
        return intent?.toUri(Intent.URI_INTENT_SCHEME)
    }
}
