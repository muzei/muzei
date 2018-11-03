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

package com.google.android.apps.muzei

import android.app.Activity
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.android.apps.muzei.sources.SourceManager
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Simple activity that just triggers the 'Next Artwork' action and finishes
 */
class NextArtworkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GlobalScope.launch {
            FirebaseAnalytics.getInstance(this@NextArtworkActivity).logEvent(
                    "next_artwork", bundleOf(
                    FirebaseAnalytics.Param.CONTENT_TYPE to "activity_shortcut"))
            SourceManager.nextArtwork(this@NextArtworkActivity)
        }
        finish()
    }
}
