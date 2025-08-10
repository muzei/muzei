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

package com.google.android.apps.muzei.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.compose.content
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.collectIn
import net.nurik.roman.muzei.R

class BrowseProviderFragment : Fragment() {
    private val viewModel: BrowseProviderViewModel by viewModels()
    private val args: BrowseProviderFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = content {
        AppTheme {
            BrowseProvider(
                contentUri = args.contentUri,
                onUp = {
                    val navController = findNavController()
                    if (navController.currentDestination?.id == R.id.browse_provider) {
                        navController.popBackStack()
                    }
                },
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.providerInfo.collectIn(viewLifecycleOwner) { providerInfo ->
            if (providerInfo == null) {
                // The contentUri is no longer valid, so we should pop
                findNavController().popBackStack()
            }
        }
    }
}