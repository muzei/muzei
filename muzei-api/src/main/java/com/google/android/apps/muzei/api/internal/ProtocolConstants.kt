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
public object ProtocolConstants {
    private const val PREFIX = "com.google.android.apps.muzei.api."
    public const val METHOD_GET_VERSION: String = PREFIX + "GET_VERSION"
    public const val KEY_VERSION: String = PREFIX + "VERSION"
    public const val DEFAULT_VERSION: Int = 310000
    public const val METHOD_REQUEST_LOAD: String = PREFIX + "REQUEST_LOAD"
    public const val METHOD_MARK_ARTWORK_INVALID: String = PREFIX + "MARK_ARTWORK_INVALID"
    public const val METHOD_MARK_ARTWORK_LOADED: String = PREFIX + "MARK_ARTWORK_LOADED"
    public const val METHOD_GET_LOAD_INFO: String = PREFIX + "GET_LOAD_INFO"
    public const val KEY_MAX_LOADED_ARTWORK_ID: String = PREFIX + "MAX_LOADED_ARTWORK_ID"
    public const val KEY_LAST_LOADED_TIME: String = PREFIX + "LAST_LOAD_TIME"
    public const val KEY_RECENT_ARTWORK_IDS: String = PREFIX + "RECENT_ARTWORK_IDS"
    public const val METHOD_GET_DESCRIPTION: String = PREFIX + "GET_DESCRIPTION"
    public const val KEY_DESCRIPTION: String = PREFIX + "DESCRIPTION"
    public const val METHOD_GET_COMMANDS: String = PREFIX + "GET_COMMANDS"
    public const val GET_COMMAND_ACTIONS_MIN_VERSION: Int = 340000
    public const val KEY_COMMANDS: String = PREFIX + "COMMANDS"
    public const val METHOD_TRIGGER_COMMAND: String = PREFIX + "TRIGGER_COMMAND"
    public const val KEY_COMMAND_AUTHORITY: String = PREFIX + "AUTHORITY"
    public const val KEY_COMMAND_ARTWORK_ID: String = PREFIX + "ARTWORK_ID"
    public const val KEY_COMMAND: String = PREFIX + "COMMAND"
    public const val METHOD_OPEN_ARTWORK_INFO: String = PREFIX + "OPEN_ARTWORK_INFO"
    public const val KEY_OPEN_ARTWORK_INFO_SUCCESS: String = PREFIX + "ARTWORK_INFO_SUCCESS"
    public const val GET_ARTWORK_INFO_MIN_VERSION: Int = 320000
    public const val METHOD_GET_ARTWORK_INFO: String = PREFIX + "GET_ARTWORK_INFO"
    public const val KEY_GET_ARTWORK_INFO: String = PREFIX + "ARTWORK_INFO"
}
