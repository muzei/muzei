/*
 * Copyright 2019 Google Inc.
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

package com.google.android.apps.muzei.legacy

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.apps.muzei.util.toast
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.LegacySourceInfoFragmentBinding
import net.nurik.roman.muzei.databinding.LegacySourceInfoItemBinding

class LegacySourceInfoFragment : Fragment(R.layout.legacy_source_info_fragment) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = LegacySourceInfoFragmentBinding.bind(view)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }
        if (binding.learnMore != null) {
            binding.learnMore.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, LegacySourceManager.LEARN_MORE_LINK))
            }
        } else {
            binding.toolbar.inflateMenu(R.menu.legacy_source_info_fragment)
            binding.toolbar.setOnMenuItemClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, LegacySourceManager.LEARN_MORE_LINK))
                true
            }
        }
        val adapter = LegacySourceListAdapter()
        binding.list.adapter = adapter
        LegacySourceManager.getInstance(requireContext()).unsupportedSources.observe(viewLifecycleOwner) {
            if (it.isEmpty()) {
                requireContext().toast(R.string.legacy_source_all_uninstalled)
                findNavController().popBackStack()
            } else {
                adapter.submitList(it)
            }
        }
    }

    inner class LegacySourceViewHolder(
            private val binding: LegacySourceInfoItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(legacySourceInfo: LegacySourceInfo) = legacySourceInfo.run {
            binding.icon.setImageBitmap(icon)
            binding.title.text = title
            binding.appInfo.setOnClickListener {
                FirebaseAnalytics.getInstance(requireContext()).logEvent(
                        "legacy_source_info_app_info_open", bundleOf(
                        FirebaseAnalytics.Param.ITEM_ID to packageName,
                        FirebaseAnalytics.Param.ITEM_NAME to title))
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = "package:$packageName".toUri()
                startActivity(intent)
            }
            val intent = Intent(Intent.ACTION_VIEW,
                    "https://play.google.com/store/apps/details?id=$packageName".toUri())
            binding.sendFeedback.isVisible =
                    intent.resolveActivity(requireContext().packageManager) != null
            binding.sendFeedback.setOnClickListener {
                FirebaseAnalytics.getInstance(requireContext()).logEvent(
                        "legacy_source_info_send_feedback_open", bundleOf(
                        FirebaseAnalytics.Param.ITEM_ID to packageName,
                        FirebaseAnalytics.Param.ITEM_NAME to title))
                startActivity(intent)
            }
        }
    }

    inner class LegacySourceListAdapter : ListAdapter<LegacySourceInfo, LegacySourceViewHolder>(
            object : DiffUtil.ItemCallback<LegacySourceInfo>() {
                override fun areItemsTheSame(
                        legacySourceInfo1: LegacySourceInfo,
                        legacySourceInfo2: LegacySourceInfo
                ) = legacySourceInfo1.packageName == legacySourceInfo2.packageName

                override fun areContentsTheSame(
                        legacySourceInfo1: LegacySourceInfo,
                        legacySourceInfo2: LegacySourceInfo
                ) = legacySourceInfo1 == legacySourceInfo2
            }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                LegacySourceViewHolder(LegacySourceInfoItemBinding.inflate(layoutInflater,
                        parent, false))

        override fun onBindViewHolder(holder: LegacySourceViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }
}
