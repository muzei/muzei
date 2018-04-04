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
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import androidx.core.widget.toast
import com.google.android.apps.muzei.single.SingleArtSource
import com.google.android.apps.muzei.sources.SourceManager
import com.google.android.apps.muzei.util.observeOnce
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

class PhotoSetAsTargetActivity : Activity() {

    companion object {
        private const val TAG = "PhotoSetAsTarget"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.data?.apply {
            val context = this@PhotoSetAsTargetActivity
            SingleArtSource.setArtwork(context, this).observeOnce { success ->
                if (success == false) {
                    Log.e(TAG, "Unable to insert artwork for ${this@apply}")
                    context.toast(R.string.set_as_wallpaper_failed)
                    finish()
                    return@observeOnce
                }

                // If adding the artwork succeeded, select the single artwork source
                val bundle = bundleOf(FirebaseAnalytics.Param.ITEM_ID to
                        ComponentName(context, SingleArtSource::class.java).flattenToShortString(),
                        FirebaseAnalytics.Param.CONTENT_TYPE to "sources")
                FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
                SourceManager.selectSource(context, SingleArtSource::class) {
                    startActivity(Intent.makeMainActivity(ComponentName(
                            context, MuzeiActivity::class.java))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    finish()
                }
            }
        } ?: finish()
    }
}
