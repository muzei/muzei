/*
 * Copyright 2020 Google Inc.
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

import android.content.res.Resources
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.observe
import androidx.wear.ambient.AmbientModeSupport
import com.google.android.apps.muzei.util.filterNotNull
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.ktx.Firebase
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.databinding.MuzeiActivityBinding
import java.text.SimpleDateFormat
import java.util.Locale

class MuzeiActivity : FragmentActivity(),
        AmbientModeSupport.AmbientCallbackProvider {
    companion object {
        private const val FACTOR = 0.146467f // c = a * sqrt(2)
    }

    private val timeFormat12h = SimpleDateFormat("h:mm", Locale.getDefault())
    private val timeFormat24h = SimpleDateFormat("H:mm", Locale.getDefault())

    private val ambientCallback: AmbientModeSupport.AmbientCallback =
            object : AmbientModeSupport.AmbientCallback() {

                override fun onEnterAmbient(ambientDetails: Bundle?) {
                    binding.time.isVisible = true
                    updateTime()
                }

                override fun onUpdateAmbient() {
                    updateTime()
                }

                fun updateTime() {
                    binding.time.text = if (DateFormat.is24HourFormat(this@MuzeiActivity))
                        timeFormat24h.format(System.currentTimeMillis())
                    else
                        timeFormat12h.format(System.currentTimeMillis())
                }

                override fun onExitAmbient() {
                    binding.time.isVisible = false
                }
            }
    private lateinit var ambientController: AmbientModeSupport.AmbientController

    private lateinit var binding: MuzeiActivityBinding

    private val artworkViewModel: MuzeiArtworkViewModel by viewModels()
    private val nextArtworkViewModel: MuzeiNextArtworkViewModel by viewModels()
    private val commandViewModel: MuzeiCommandViewModel by viewModels()
    private val providerViewModel: MuzeiProviderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        binding = MuzeiActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.scrollView.requestFocus()

        if (resources.configuration.isScreenRound) {
            val inset = (FACTOR * Resources.getSystem().displayMetrics.widthPixels).toInt()
            binding.content.setPadding(inset, 0, inset, inset)
        }
        binding.artworkInfo.create()
        binding.nextArtwork.create()
        binding.providerInfo.create()

        artworkViewModel.artworkLiveData.filterNotNull().observe(this) { artwork ->
            binding.artworkInfo.bind(artwork)
        }

        nextArtworkViewModel.providerLiveData.observe(this) { provider ->
            binding.nextArtwork.nextArtwork.isVisible = provider?.supportsNextArtwork == true
        }

        commandViewModel.commandsLiveData.observe(this) { commands ->
            // TODO Show multiple commands rather than only the first
            val command = commands.filterNot { action ->
                action.title.isBlank()
            }.firstOrNull { action ->
                action.shouldShowIcon()
            }
            if (command != null) {
                binding.command.bind(command)
                binding.command.command.isVisible = true
            } else {
                binding.command.command.isVisible = false
            }
        }

        providerViewModel.providerLiveData.observe(this) { provider ->
            binding.providerInfo.bind(provider)
        }
        ProviderChangedReceiver.observeForVisibility(this, this)
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}
