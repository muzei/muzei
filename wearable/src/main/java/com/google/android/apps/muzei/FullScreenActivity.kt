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

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.wear.ambient.AmbientModeSupport
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.PanView
import com.google.android.apps.muzei.util.coroutineScope
import com.google.android.apps.muzei.util.observeNonNull
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

class FullScreenActivity : FragmentActivity(),
        AmbientModeSupport.AmbientCallbackProvider {
    private val ambientCallback: AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {
                override fun onEnterAmbient(ambientDetails: Bundle?) {
                    finish()
                }
            }

    private lateinit var panView: PanView
    private lateinit var loadingIndicatorView: View

    private var showLoadingIndicator: Job? = null

    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        AmbientModeSupport.attach(this)
        setContentView(R.layout.full_screen_activity)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        panView = findViewById(R.id.pan_view)

        loadingIndicatorView = findViewById(R.id.loading_indicator)
        showLoadingIndicator = coroutineScope.launch(Dispatchers.Main) {
            delay(500)
            loadingIndicatorView.isVisible = true
        }

        MuzeiDatabase.getInstance(this).artworkDao()
                .currentArtwork.observeNonNull(this) { artwork ->
            coroutineScope.launch(Dispatchers.Main) {
                val image = ImageLoader.decode(
                        contentResolver, artwork.contentUri)
                showLoadingIndicator?.cancel()
                loadingIndicatorView.isVisible = false
                panView.isVisible = true
                panView.setImage(image)
            }
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}
