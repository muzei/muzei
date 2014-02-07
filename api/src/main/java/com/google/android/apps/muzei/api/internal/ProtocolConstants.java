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

    private ProtocolConstants() {
    }
}
