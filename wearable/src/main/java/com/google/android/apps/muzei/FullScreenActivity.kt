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
import androidx.wear.ambient.AmbientModeSupport
import com.google.android.apps.muzei.render.ImageLoader
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.launchWhenStartedIn
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
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
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        showLoadingIndicator = lifecycleScope.launch(Dispatchers.Main) {
            delay(500)
            binding.loadingIndicator.isVisible = true
        }

        val database = MuzeiDatabase.getInstance(this@FullScreenActivity)
        database.artworkDao().currentArtwork.filterNotNull().onEach { artwork ->
            val image = ImageLoader.decode(
                    contentResolver, artwork.contentUri)
            showLoadingIndicator?.cancel()
            binding.loadingIndicator.isVisible = false
            binding.panView.isVisible = true
            binding.panView.setImage(image)
        }.launchWhenStartedIn(this)
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}
