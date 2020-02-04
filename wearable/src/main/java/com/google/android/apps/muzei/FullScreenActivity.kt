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
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.wear.ambient.AmbientModeSupport
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.filterNotNull
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.databinding.FullScreenActivityBinding

class FullScreenActivity : FragmentActivity(),
        AmbientModeSupport.AmbientCallbackProvider {
    private val ambientCallback: AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {
                override fun onEnterAmbient(ambientDetails: Bundle?) {
                    finish()
                }
            }

    private lateinit var binding: FullScreenActivityBinding

    private var showLoadingIndicator: Job? = null

    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        AmbientModeSupport.attach(this)
        binding = FullScreenActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        showLoadingIndicator = lifecycleScope.launch(Dispatchers.Main) {
            delay(500)
            binding.loadingIndicator.isVisible = true
        }

        MuzeiDatabase.getInstance(this).artworkDao()
                .currentArtwork.filterNotNull().observe(this) { artwork ->
            lifecycleScope.launch(Dispatchers.Main) {
                val image = ImageLoader.decode(
                        contentResolver, artwork.contentUri)
                showLoadingIndicator?.cancel()
                binding.loadingIndicator.isVisible = false
                binding.panView.isVisible = true
                binding.panView.setImage(image)
            }
        }
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}
