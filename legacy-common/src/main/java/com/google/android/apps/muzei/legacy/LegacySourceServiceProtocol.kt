/*
 * Copyright 2019 Google Inc.
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

package com.google.android.apps.muzei.legacy

import android.content.Intent

/**
 * Constants used as the protocol to communicate with the LegacySourceService.
 */
object LegacySourceServiceProtocol {
    /**
     * The [Intent] action representing a Muzei art source. This service should
     * declare an `<intent-filter>` for this action in order to register with
     * Muzei.
     */
    const val ACTION_MUZEI_ART_SOURCE = "com.google.android.apps.muzei.api.MuzeiArtSource"
    const val LEGACY_COMMAND_ID_NEXT_ARTWORK = 1001
    const val LEGACY_SOURCE_ACTION = "com.google.android.apps.muzei.legacy"

    const val WHAT_REGISTER_REPLY_TO = 0
    const val WHAT_UNREGISTER_REPLY_TO = 1
    const val WHAT_NEXT_ARTWORK = 2
    const val WHAT_ALLOWS_NEXT_ARTWORK = 3

    const val WHAT_REPLY_TO_REPLACEMENT = 0
    const val WHAT_REPLY_TO_NO_SELECTED_SOURCE = 1
}
