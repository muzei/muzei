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
package com.google.android.apps.muzei.api.internal

import android.content.SharedPreferences
import android.os.Bundle
import java.util.ArrayDeque

/**
 * Save the given [ids] into [SharedPreferences] for later retrieval via [getRecentIds].
 */
fun SharedPreferences.Editor.putRecentIds(key: String, ids: ArrayDeque<Long>) {
    putString(key, ids.joinToString(","))
}

/**
 * Gets the recent ids out of a [SharedPreferences].
 */
fun SharedPreferences.getRecentIds(key: String): ArrayDeque<Long> {
    val idsString = getString(key, null) ?: ""
    return idsString.toRecentIds()
}

/**
 * Gets the recent ids out of a [Bundle].
 */
fun Bundle.getRecentIds(key: String) = getString(key, "").toRecentIds()

private fun String.toRecentIds(): ArrayDeque<Long> {
    val ids = ArrayDeque<Long>()
    splitToSequence(',').filter {
        it.isNotEmpty()
    }.map {
        it.toLong()
    }.forEach { id ->
        // Remove the id if it exists in the list already
        ids.remove(id)
        // Then add it to the end of the list
        ids.add(id)
    }
    return ids
}
