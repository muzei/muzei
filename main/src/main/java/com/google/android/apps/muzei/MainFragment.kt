/*
 * Copyright 2017 Google Inc.
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

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.apps.muzei.browse.BrowseProviderFragment
import com.google.android.apps.muzei.settings.EffectsFragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

/**
 * Fragment which controls the main view of the Muzei app and handles the bottom navigation
 * between various screens.
 */
class MainFragment : Fragment(R.layout.main_fragment), ChooseProviderFragment.Callbacks {

    private lateinit var bottomNavigationView: BottomNavigationView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Set up the container for the child fragments
        val container = view.findViewById<View>(R.id.container)
        val navController = container.findNavController()

        // Set up the bottom nav
        bottomNavigationView = view.findViewById(R.id.bottom_nav)
        bottomNavigationView.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.main_art_details -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "ArtDetail",
                                    ArtDetailFragment::class.java.simpleName)
                }
                R.id.main_choose_provider -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "ChooseProvider",
                                    ChooseProviderFragment::class.java.simpleName)
                }
                R.id.browse_provider -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "BrowseProvider",
                                    BrowseProviderFragment::class.java.simpleName)
                }
                R.id.main_effects -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "Effects",
                                    EffectsFragment::class.java.simpleName)
                }
            }
        }
        bottomNavigationView.setOnNavigationItemReselectedListener { item ->
            if (item.itemId == R.id.main_art_details) {
                activity?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_LOW_PROFILE
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_IMMERSIVE
                        or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
        }

        // Send the correct window insets to each view
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            // Ensure the container gets the appropriate insets
            ViewCompat.dispatchApplyWindowInsets(container,
                    insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft,
                            insets.systemWindowInsetTop,
                            insets.systemWindowInsetRight,
                            0))
            ViewCompat.dispatchApplyWindowInsets(bottomNavigationView,
                    insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft,
                            0,
                            insets.systemWindowInsetRight,
                            insets.systemWindowInsetBottom))
            insets.consumeSystemWindowInsets().consumeDisplayCutout()
        }

        // Listen for visibility changes to know when to hide our views
        view.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0

            bottomNavigationView.visibility = View.VISIBLE
            bottomNavigationView.animate()
                    .alpha(if (visible) 1f else 0f)
                    .setDuration(200)
                    .withEndAction {
                        if (!visible) {
                            bottomNavigationView.visibility = View.GONE
                        }
                        updateNavigationBarColor()
                    }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        updateNavigationBarColor()
    }

    private fun updateNavigationBarColor() {
        activity?.window?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val lightNavigationBar = resources.getBoolean(R.bool.light_navigation_bar)
                if (lightNavigationBar) {
                    decorView.systemUiVisibility = decorView.systemUiVisibility or
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    decorView.systemUiVisibility = decorView.systemUiVisibility xor
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
        }
    }

    override fun onRequestCloseActivity() {
        bottomNavigationView.selectedItemId = R.id.main_art_details
    }
}
