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
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.openArtworkInfo
import com.google.android.apps.muzei.util.coroutineScope
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.launch

/**
 * Open the Artwork Info associated with the current artwork
 */
class ArtworkInfoRedirectActivity : FragmentActivity() {
    companion object {
        private const val EXTRA_FROM = "from"

        fun getIntent(context: Context, from: String): Intent =
                Intent(context, ArtworkInfoRedirectActivity::class.java).apply {
                    action = "com.google.android.apps.muzei.OPEN_ARTWORK_INFO"
                    putExtra(EXTRA_FROM, from)
                }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coroutineScope.launch {
            val artwork = MuzeiDatabase.getInstance(this@ArtworkInfoRedirectActivity)
                    .artworkDao()
                    .getCurrentArtwork()
            artwork?.run {
                val from = intent?.getStringExtra(EXTRA_FROM) ?: "activity_shortcut"
                FirebaseAnalytics.getInstance(this@ArtworkInfoRedirectActivity).logEvent(
                        "artwork_info_open", bundleOf(
                        FirebaseAnalytics.Param.CONTENT_TYPE to from))
                openArtworkInfo(this@ArtworkInfoRedirectActivity)
            }
            finish()
        }
    }
}
