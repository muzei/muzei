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
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.ConcatAdapter
import androidx.wear.ambient.AmbientModeSupport
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.databinding.MuzeiWearActivityBinding
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

    private lateinit var binding: MuzeiWearActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientController = AmbientModeSupport.attach(this)
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        binding = MuzeiWearActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.recyclerView.requestFocus()

        if (resources.configuration.isScreenRound) {
            val inset = (FACTOR * Resources.getSystem().displayMetrics.widthPixels).toInt()
            binding.recyclerView.setPadding(inset, 0, inset, inset)
        }
        val artworkAdapter = MuzeiArtworkAdapter(this)
        val nextArtworkAdapter = MuzeiNextArtworkAdapter(this)
        val commandArtworkAdapter = MuzeiCommandAdapter(this)
        val providerAdapter = MuzeiProviderAdapter(this)

        binding.recyclerView.adapter = ConcatAdapter(
                artworkAdapter,
                nextArtworkAdapter,
                commandArtworkAdapter,
                providerAdapter)

        ProviderChangedReceiver.observeForVisibility(this, this)
    }

    override fun getAmbientCallback(): AmbientModeSupport.AmbientCallback {
        return ambientCallback
    }
}