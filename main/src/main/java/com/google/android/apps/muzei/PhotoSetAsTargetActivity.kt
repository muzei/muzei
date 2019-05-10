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

import android.content.ComponentName
import android.content.Intent
import android.util.Log
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.apps.muzei.single.BuildConfig.SINGLE_AUTHORITY
import com.google.android.apps.muzei.single.SingleArtProvider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

class PhotoSetAsTargetActivity : FragmentActivity() {

    companion object {
        private const val TAG = "PhotoSetAsTarget"
    }

    init {
        lifecycleScope.launchWhenCreated {
            val uri = intent?.data ?: run {
                finish()
                return@launchWhenCreated
            }
            val context = this@PhotoSetAsTargetActivity
            val success = SingleArtProvider.setArtwork(context, uri)
            if (!success) {
                Log.e(TAG, "Unable to insert artwork for $uri")
                toast(R.string.set_as_wallpaper_failed)
                finish()
                return@launchWhenCreated
            }

            // If adding the artwork succeeded, select the single artwork provider
            FirebaseAnalytics.getInstance(context).logEvent(
                    FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                    FirebaseAnalytics.Param.ITEM_ID to SINGLE_AUTHORITY,
                    FirebaseAnalytics.Param.ITEM_CATEGORY to "providers",
                    FirebaseAnalytics.Param.CONTENT_TYPE to "set_as"))
            ProviderManager.select(context, SINGLE_AUTHORITY)
            startActivity(Intent.makeMainActivity(ComponentName(
                    context, MuzeiActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}
