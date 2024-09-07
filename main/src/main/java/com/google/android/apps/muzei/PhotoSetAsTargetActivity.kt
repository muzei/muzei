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
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.apps.muzei.api.MuzeiContract
import com.google.android.apps.muzei.single.BuildConfig.SINGLE_AUTHORITY
import com.google.android.apps.muzei.single.SingleArtProvider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

class PhotoSetAsTargetActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PhotoSetAsTarget"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(NonCancellable) {
            val uri = intent?.data ?: run {
                finish()
                return@launch
            }
            val context = this@PhotoSetAsTargetActivity
            val success = SingleArtProvider.setArtwork(context, uri)
            if (!success) {
                Log.e(TAG, "Unable to insert artwork for $uri")
                toast(R.string.set_as_wallpaper_failed)
                finish()
                return@launch
            }

            // If adding the artwork succeeded, select the single artwork provider
            if (!MuzeiContract.Sources.isProviderSelected(context, SINGLE_AUTHORITY)) {
                Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                    param(FirebaseAnalytics.Param.ITEM_LIST_ID, SINGLE_AUTHORITY)
                    param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                    param(FirebaseAnalytics.Param.CONTENT_TYPE, "set_as")
                }
                ProviderManager.select(context, SINGLE_AUTHORITY)
            }
            startActivity(Intent.makeMainActivity(ComponentName(
                    context, MuzeiActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
        }
    }
}