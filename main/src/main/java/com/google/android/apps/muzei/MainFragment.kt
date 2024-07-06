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

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.apps.muzei.browse.BrowseProviderFragment
import com.google.android.apps.muzei.settings.EffectsFragment
import com.google.android.apps.muzei.util.autoCleared
import com.google.android.apps.muzei.util.collectIn
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.flow.map
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MainFragmentBinding

/**
 * Fragment which controls the main view of the Muzei app and handles the bottom navigation
 * between various screens.
 */
class MainFragment : Fragment(R.layout.main_fragment), ChooseProviderFragment.Callbacks {

    private val darkStatusBarColor by lazy {
        ContextCompat.getColor(requireContext(), R.color.md_theme_background)
    }
    private var binding: MainFragmentBinding by autoCleared()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Set up the container for the child fragments
        binding = MainFragmentBinding.bind(view)
        val navHostFragment = binding.container.getFragment<NavHostFragment>()
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.main_navigation)
        if (requireActivity().isPreviewMode) {
            // Make the Effects screen the start destination when
            // coming from the Settings button on the Live Wallpaper picker
            navGraph.setStartDestination(R.id.main_effects)
        }
        navController.graph = navGraph

        // Set up the bottom nav
        (binding.navBar as NavigationBarView).setupWithNavController(navController)
        // React to the destination changing to update the status bar color
        navController.currentBackStackEntryFlow.map { backStackEntry ->
            backStackEntry.arguments
        }.collectIn(viewLifecycleOwner) { args ->
            requireActivity().window.statusBarColor =
                if (args?.getBoolean("useDarkStatusBar") == true) {
                    darkStatusBarColor
                } else {
                    Color.TRANSPARENT
                }
        }
        // React to the destination changing for analytics
        navController.currentBackStackEntryFlow.map { backStackEntry ->
            backStackEntry.destination.id
        }.collectIn(viewLifecycleOwner) { destinationId ->
            when (destinationId) {
                R.id.main_art_details -> {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "ArtDetail")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                ArtDetailFragment::class.java.simpleName)
                    }
                }
                R.id.main_choose_provider -> {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "ChooseProvider")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                ChooseProviderFragment::class.java.simpleName)
                    }
                }
                R.id.browse_provider -> {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "BrowseProvider")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                BrowseProviderFragment::class.java.simpleName)
                    }
                }
                R.id.main_effects -> {
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "Effects")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                EffectsFragment::class.java.simpleName)
                    }
                }
            }
        }
        (binding.navBar as NavigationBarView).setOnItemReselectedListener { item ->
            if (item.itemId == R.id.main_art_details) {
                @Suppress("DEPRECATION")
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
        @Suppress("DEPRECATION")
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            // Ensure the container gets the appropriate insets
            if (binding.navBar is BottomNavigationView) {
                ViewCompat.dispatchApplyWindowInsets(binding.container,
                    WindowInsetsCompat.Builder(insets).setSystemWindowInsets(Insets.of(
                        insets.systemWindowInsetLeft,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        0)).build())
            } else if (binding.navBar is NavigationRailView) {
                ViewCompat.dispatchApplyWindowInsets(binding.container,
                    WindowInsetsCompat.Builder(insets).setSystemWindowInsets(Insets.of(
                        0,
                        insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight,
                        insets.systemWindowInsetBottom)).build())
            }
            ViewCompat.dispatchApplyWindowInsets(binding.navBar,
                WindowInsetsCompat.Builder(insets).setSystemWindowInsets(Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom)).build())
            insets.consumeSystemWindowInsets().consumeDisplayCutout()
        }

        // Listen for visibility changes to know when to hide our views
        @Suppress("DEPRECATION")
        view.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0

            with(binding.navBar) {
                visibility = View.VISIBLE
                animate()
                    .alpha(if (visible) 1f else 0f)
                    .setDuration(200)
                    .withEndAction {
                        if (!visible) {
                            visibility = View.GONE
                        }
                        updateNavigationBarColor()
                    }
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
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = decorView.systemUiVisibility or
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                } else {
                    @Suppress("DEPRECATION")
                    decorView.systemUiVisibility = decorView.systemUiVisibility and
                            View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
                }
            }
        }
    }

    override fun onRequestCloseActivity() {
        (binding.navBar as NavigationBarView).selectedItemId = R.id.main_art_details
    }
}