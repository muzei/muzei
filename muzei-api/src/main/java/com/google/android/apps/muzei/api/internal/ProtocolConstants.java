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

package com.google.android.apps.muzei.api.internal;

/**
 * Internal intent constants for sources.
 */
public class ProtocolConstants {
    // Received intents
    public static final String ACTION_SUBSCRIBE = "com.google.android.apps.muzei.api.action.SUBSCRIBE";
    public static final String EXTRA_SUBSCRIBER_COMPONENT = "com.google.android.apps.muzei.api.extra.SUBSCRIBER_COMPONENT";
    public static final String EXTRA_TOKEN = "com.google.android.apps.muzei.api.extra.TOKEN";

    public static final String ACTION_HANDLE_COMMAND = "com.google.android.apps.muzei.api.action.HANDLE_COMMAND";
    public static final String EXTRA_COMMAND_ID = "com.google.android.apps.muzei.api.extra.COMMAND_ID";
    public static final String EXTRA_SCHEDULED = "com.google.android.apps.muzei.api.extra.SCHEDULED";

    public static final String ACTION_NETWORK_AVAILABLE = "com.google.android.apps.muzei.api.action.NETWORK_AVAILABLE";

    // Sent intents
    public static final String ACTION_PUBLISH_STATE = "com.google.android.apps.muzei.api.action.PUBLISH_UPDATE";
    public static final String EXTRA_STATE = "com.google.android.apps.muzei.api.extra.STATE";

    // MuzeiArtProvider
    private static final String PREFIX = "com.google.android.apps.muzei.api.";
    public static final String METHOD_REQUEST_LOAD = PREFIX + "REQUEST_LOAD";
    public static final String METHOD_MARK_ARTWORK_INVALID = PREFIX + "MARK_ARTWORK_INVALID";
    public static final String METHOD_MARK_ARTWORK_LOADED = PREFIX + "MARK_ARTWORK_LOADED";
    public static final String METHOD_GET_LOAD_INFO = PREFIX + "GET_LOAD_INFO";
    public static final String KEY_MAX_LOADED_ARTWORK_ID = PREFIX + "MAX_LOADED_ARTWORK_ID";
    public static final String KEY_LAST_LOADED_TIME = PREFIX + "LAST_LOAD_TIME";
    public static final String KEY_RECENT_ARTWORK_IDS = PREFIX + "RECENT_ARTWORK_IDS";
    public static final String METHOD_GET_DESCRIPTION = PREFIX + "GET_DESCRIPTION";
    public static final String KEY_DESCRIPTION = PREFIX + "DESCRIPTION";
    public static final String METHOD_GET_COMMANDS = PREFIX + "GET_COMMANDS";
    public static final String KEY_COMMANDS = PREFIX + "COMMANDS";
    public static final String METHOD_TRIGGER_COMMAND = PREFIX + "TRIGGER_COMMAND";
    public static final String KEY_COMMAND = PREFIX + "COMMAND";
    public static final String METHOD_OPEN_ARTWORK_INFO = PREFIX + "OPEN_ARTWORK_INFO";
    public static final String KEY_OPEN_ARTWORK_INFO_SUCCESS = PREFIX + "ARTWORK_INFO_SUCCESS";

    private ProtocolConstants() {
    }
}
