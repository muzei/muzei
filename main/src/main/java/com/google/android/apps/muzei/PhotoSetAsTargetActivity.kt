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
import android.arch.lifecycle.Observer
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.os.bundleOf
import com.google.android.apps.muzei.single.SingleArtSource
import com.google.android.apps.muzei.sources.SourceManager
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

private const val TAG = "PhotoSetAsTarget"

class PhotoSetAsTargetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.data?.apply {
            val context = this@PhotoSetAsTargetActivity
            val insertLiveData = SingleArtSource.setArtwork(context, this)
            insertLiveData.observeForever(object : Observer<Boolean> {
                override fun onChanged(success: Boolean?) {
                    insertLiveData.removeObserver(this)
                    if (success == false) {
                        Log.e(TAG, "Unable to insert artwork for $this@run")
                        Toast.makeText(context, R.string.set_as_wallpaper_failed, Toast.LENGTH_SHORT).show()
                        finish()
                        return
                    }

                    // If adding the artwork succeeded, select the single artwork source
                    val bundle = bundleOf(FirebaseAnalytics.Param.ITEM_ID to
                            ComponentName(context, SingleArtSource::class.java).flattenToShortString(),
                            FirebaseAnalytics.Param.CONTENT_TYPE to "sources")
                    FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.SELECT_CONTENT, bundle)
                    SourceManager.selectSource(context, ComponentName(context, SingleArtSource::class.java)) {
                        startActivity(Intent.makeMainActivity(ComponentName(
                                context, MuzeiActivity::class.java))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        finish()
                    }
                }
            })
        } ?: finish()
    }
}
