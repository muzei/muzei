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
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.observe
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.WearableLinearLayoutManager
import androidx.wear.widget.WearableRecyclerView
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.firebase.analytics.FirebaseAnalytics
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

class ChooseProviderActivity : FragmentActivity() {

    companion object {
        private const val TAG = "ChooseProviderFragment"
        private const val REQUEST_EXTENSION_SETUP = 1
        private const val START_ACTIVITY_PROVIDER = "startActivityProvider"
    }

    private val viewModel: ChooseProviderViewModel by viewModels()

    private val adapter = ProviderAdapter()

    private var startActivityProvider: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityProvider = savedInstanceState?.getString(START_ACTIVITY_PROVIDER)
        setContentView(R.layout.choose_provider_activity)
        val providerList = findViewById<WearableRecyclerView>(R.id.provider_list)
        providerList.isEdgeItemsCenteringEnabled = true
        providerList.layoutManager = WearableLinearLayoutManager(this)
        providerList.adapter = adapter

        viewModel.providers.observe(this) { providers ->
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
            val setupIntent = Intent()
                    .setComponent(provider.setupActivity)
                    .putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
            startActivityForResult(setupIntent, REQUEST_EXTENSION_SETUP)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Can't launch provider setup.", e)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_EXTENSION_SETUP -> {
                val provider = startActivityProvider
                if (resultCode == Activity.RESULT_OK && provider != null) {
                    FirebaseAnalytics.getInstance(this).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to provider,
                            FirebaseAnalytics.Param.CONTENT_TYPE to "providers",
                            FirebaseAnalytics.Param.CONTENT_TYPE to "after_setup"))
                    GlobalScope.launch {
                        ProviderManager.select(this@ChooseProviderActivity, provider)
                        finish()
                    }
                }
                startActivityProvider = null
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    inner class ProviderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun setProviderInfo(providerInfo: ProviderInfo) {
            val chooseProvider = itemView as Button
            val size = resources.getDimensionPixelSize(R.dimen.choose_provider_image_size)
            providerInfo.icon.bounds = Rect(0, 0, size, size)
            chooseProvider.setCompoundDrawablesRelative(providerInfo.icon,
                    null, null, null)
            chooseProvider.text = providerInfo.title
            chooseProvider.setOnClickListener {
                if (providerInfo.setupActivity != null) {
                    launchProviderSetup(providerInfo)
                } else {
                    FirebaseAnalytics.getInstance(this@ChooseProviderActivity).logEvent(
                            FirebaseAnalytics.Event.SELECT_CONTENT, bundleOf(
                            FirebaseAnalytics.Param.ITEM_ID to providerInfo.authority,
                            FirebaseAnalytics.Param.CONTENT_TYPE to "providers",
                            FirebaseAnalytics.Param.CONTENT_TYPE to "choose"))
                    GlobalScope.launch {
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
                ProviderViewHolder(layoutInflater.inflate(
                        R.layout.choose_provider_item,
                        parent, false))

        override fun onBindViewHolder(holder: ProviderViewHolder, position: Int) {
            holder.setProviderInfo(getItem(position))
        }
    }
}
