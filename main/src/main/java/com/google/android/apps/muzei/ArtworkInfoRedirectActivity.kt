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

package com.google.android.apps.muzei

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v4.app.FragmentActivity
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.util.coroutineScope
import kotlinx.coroutines.experimental.launch

/**
 * Open the Artwork Info associated with the current artwork
 */
class ArtworkInfoRedirectActivity : FragmentActivity() {
    companion object {
        fun getIntent(context: Context): Intent =
                Intent(context, ArtworkInfoRedirectActivity::class.java).apply {
                    action = "com.google.android.apps.muzei.OPEN_ARTWORK_INFO"
                }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coroutineScope.launch {
            val artwork = MuzeiDatabase.getInstance(this@ArtworkInfoRedirectActivity)
                    .artworkDao()
                    .getCurrentArtwork()
            artwork?.openArtworkInfo(this@ArtworkInfoRedirectActivity)
            finish()
        }
    }
}
