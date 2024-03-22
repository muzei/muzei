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
import androidx.activity.ComponentActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.wear.ambient.AmbientLifecycleObserver
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.contentUri
import com.google.android.apps.muzei.util.collectIn
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.databinding.FullScreenActivityBinding

class FullScreenActivity : ComponentActivity() {
    private val ambientCallback: AmbientLifecycleObserver.AmbientLifecycleCallback =
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    finish()
                }
            }
    private val ambientObserver = AmbientLifecycleObserver(this, ambientCallback)

    private lateinit var binding: FullScreenActivityBinding

    private var showLoadingIndicator: Job? = null

    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        lifecycle.addObserver(ambientObserver)
        binding = FullScreenActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        showLoadingIndicator = lifecycleScope.launch(Dispatchers.Main) {
            delay(500)
            binding.loadingIndicator.isVisible = true
        }

        val database = MuzeiDatabase.getInstance(this@FullScreenActivity)
        database.artworkDao().getCurrentArtworkFlow().filterNotNull().collectIn(this) { artwork ->
            val image = ImageLoader.decode(
                    contentResolver, artwork.contentUri)
            showLoadingIndicator?.cancel()
            binding.loadingIndicator.isVisible = false
            binding.panView.isVisible = true
            binding.panView.setImage(image)
        }
    }
}