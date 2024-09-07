/*
 * Copyright 2018 Google Inc.
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
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.collectIn
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.ChooseProviderActivityBinding
import net.nurik.roman.muzei.databinding.ChooseProviderWearItemBinding

private class StartActivityFromSettings : ActivityResultContract<ComponentName, Boolean>() {
    override fun createIntent(context: Context, input: ComponentName): Intent =
            Intent().setComponent(input)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)

    override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == Activity.RESULT_OK
}

class ChooseProviderActivity : FragmentActivity() {

    companion object {
        private const val TAG = "ChooseProviderFragment"
        private const val START_ACTIVITY_PROVIDER = "startActivityProvider"
    }

    private val viewModel: ChooseProviderViewModel by viewModels()

    private val adapter = ProviderAdapter()

    private lateinit var binding: ChooseProviderActivityBinding

    private var startActivityProvider: String? = null
    private val providerSetup = registerForActivityResult(StartActivityFromSettings()) { success ->
        val provider = startActivityProvider
        if (success && provider != null) {
            Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                param(FirebaseAnalytics.Param.ITEM_LIST_ID, provider)
                param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                param(FirebaseAnalytics.Param.CONTENT_TYPE, "after_setup")
            }
            lifecycleScope.launch(NonCancellable) {
                ProviderManager.select(this@ChooseProviderActivity, provider)
                finish()
            }
        }
        startActivityProvider = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityProvider = savedInstanceState?.getString(START_ACTIVITY_PROVIDER)
        binding = ChooseProviderActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.list.isEdgeItemsCenteringEnabled = true
        binding.list.layoutManager = WearableLinearLayoutManager(this)
        binding.list.adapter = adapter

        viewModel.providers.collectIn(this) { providers ->
            adapter.submitList(providers)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(START_ACTIVITY_PROVIDER, startActivityProvider)
    }

    private fun launchProviderSetup(provider: ProviderInfo) {
        try {
            startActivityProvider = provider.authority
            providerSetup.launch(provider.setupActivity!!)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        }
    }

    inner class ProviderViewHolder(
            private val binding: ChooseProviderWearItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun setProviderInfo(providerInfo: ProviderInfo) {
            val size = resources.getDimensionPixelSize(R.dimen.choose_provider_image_size)
            providerInfo.icon.bounds = Rect(0, 0, size, size)
            binding.chooseProvider.setCompoundDrawablesRelative(providerInfo.icon,
                    null, null, null)
            binding.chooseProvider.text = providerInfo.title
            binding.chooseProvider.setOnClickListener {
                if (providerInfo.setupActivity != null) {
                    launchProviderSetup(providerInfo)
                } else {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                        param(FirebaseAnalytics.Param.ITEM_LIST_ID, providerInfo.authority)
                        param(FirebaseAnalytics.Param.ITEM_NAME, providerInfo.title)
                        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "choose")
                    }
                    lifecycleScope.launch(NonCancellable) {
                        ProviderManager.select(this@ChooseProviderActivity, providerInfo.authority)
                        finish()
                    }
                }
            }
        }
    }

    inner class ProviderAdapter : ListAdapter<ProviderInfo, ProviderViewHolder>(
            object : DiffUtil.ItemCallback<ProviderInfo>() {
                override fun areItemsTheSame(provider1: ProviderInfo, provider2: ProviderInfo) =
                        provider1.authority == provider2.authority

                override fun areContentsTheSame(provider1: ProviderInfo, provider2: ProviderInfo) =
                        false
            }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                ProviderViewHolder(ChooseProviderWearItemBinding.inflate(layoutInflater,
                        parent, false))

        override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
            holder.setProviderInfo(getItem(position))
        }
    }
}