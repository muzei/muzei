/*
 * Copyright 2014 Google Inc.
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

/**
 * Internal intent constants for sources.
 */
object ProtocolConstants {
    private const val PREFIX = "com.google.android.apps.muzei.api."
    const val METHOD_GET_VERSION = PREFIX + "GET_VERSION"
    const val KEY_VERSION = PREFIX + "VERSION"
    const val DEFAULT_VERSION = 310000
    const val METHOD_REQUEST_LOAD = PREFIX + "REQUEST_LOAD"
    const val METHOD_MARK_ARTWORK_INVALID = PREFIX + "MARK_ARTWORK_INVALID"
    const val METHOD_MARK_ARTWORK_LOADED = PREFIX + "MARK_ARTWORK_LOADED"
    const val METHOD_GET_LOAD_INFO = PREFIX + "GET_LOAD_INFO"
    const val KEY_MAX_LOADED_ARTWORK_ID = PREFIX + "MAX_LOADED_ARTWORK_ID"
    const val KEY_LAST_LOADED_TIME = PREFIX + "LAST_LOAD_TIME"
    const val KEY_RECENT_ARTWORK_IDS = PREFIX + "RECENT_ARTWORK_IDS"
    const val METHOD_GET_DESCRIPTION = PREFIX + "GET_DESCRIPTION"
    const val KEY_DESCRIPTION = PREFIX + "DESCRIPTION"
    const val METHOD_GET_COMMANDS = PREFIX + "GET_COMMANDS"
    const val KEY_COMMANDS = PREFIX + "COMMANDS"
    const val METHOD_TRIGGER_COMMAND = PREFIX + "TRIGGER_COMMAND"
    const val KEY_COMMAND = PREFIX + "COMMAND"
    const val METHOD_OPEN_ARTWORK_INFO = PREFIX + "OPEN_ARTWORK_INFO"
    const val KEY_OPEN_ARTWORK_INFO_SUCCESS = PREFIX + "ARTWORK_INFO_SUCCESS"
    const val GET_ARTWORK_INFO_MIN_VERSION = 320000
    const val METHOD_GET_ARTWORK_INFO = PREFIX + "GET_ARTWORK_INFO"
    const val KEY_GET_ARTWORK_INFO = PREFIX + "ARTWORK_INFO"
}
