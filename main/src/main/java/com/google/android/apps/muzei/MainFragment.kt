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
import android.support.design.widget.BottomNavigationView
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentTransaction
import android.support.v4.view.ViewCompat
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.Toolbar
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.apps.muzei.settings.EffectsFragment
import com.google.android.apps.muzei.sources.ChooseSourceFragment
import com.google.android.apps.muzei.util.makeCubicGradientScrimDrawable
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.R

/**
 * Fragment which controls the main view of the Muzei app and handles the bottom navigation
 * between various screens.
 */
class MainFragment : Fragment(), FragmentManager.OnBackStackChangedListener, ChooseSourceFragment.Callbacks {

    private lateinit var bottomNavigationView: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.addOnBackStackChangedListener(this)
    }

    override fun onBackStackChanged() {
        if (childFragmentManager.backStackEntryCount == 0) {
            if (bottomNavigationView.selectedItemId != R.id.main_art_details) {
                bottomNavigationView.selectedItemId = R.id.main_art_details
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Set up the action bar
        val actionBarContainer = view.findViewById<View>(R.id.action_bar_container)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            actionBarContainer.background = makeCubicGradientScrimDrawable(Gravity.TOP, 0x44)
        }
        val toolbar = view.findViewById<Toolbar>(R.id.toolbar)
        val activity = activity as AppCompatActivity?
        activity?.apply {
            setSupportActionBar(toolbar)
            supportActionBar?.setDisplayShowTitleEnabled(false)
        }

        // Set up the container for the child fragments
        val container = view.findViewById<View>(R.id.container)
        if (savedInstanceState == null) {
            FirebaseAnalytics.getInstance(requireContext())
                    .setCurrentScreen(requireActivity(), "ArtDetail",
                            ArtDetailFragment::class.java.simpleName)
            childFragmentManager.beginTransaction()
                    .replace(R.id.container, ArtDetailFragment())
                    .commit()
        }

        // Set up the bottom nav
        bottomNavigationView = view.findViewById(R.id.bottom_nav)
        bottomNavigationView.setOnNavigationItemSelectedListener { item ->
            if (childFragmentManager.isStateSaved) {
                // Can't navigate after the state is saved
                return@setOnNavigationItemSelectedListener false
            }
            return@setOnNavigationItemSelectedListener when (item.itemId) {
                R.id.main_art_details -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "ArtDetail",
                                    ArtDetailFragment::class.java.simpleName)
                    // The ArtDetailFragment is on the back stack, so just remove
                    // any other Fragment that has replaced it
                    childFragmentManager.popBackStack("main",
                            FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    true
                }
                R.id.main_choose_source -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "ChooseSource",
                                    ChooseSourceFragment::class.java.simpleName)
                    childFragmentManager.popBackStack("main",
                            FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    childFragmentManager.beginTransaction()
                            .replace(R.id.container, ChooseSourceFragment())
                            .addToBackStack("main")
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .commit()
                    true
                }
                R.id.main_effects -> {
                    FirebaseAnalytics.getInstance(requireContext())
                            .setCurrentScreen(requireActivity(), "Effects",
                                    EffectsFragment::class.java.simpleName)
                    childFragmentManager.popBackStack("main",
                            FragmentManager.POP_BACK_STACK_INCLUSIVE)
                    childFragmentManager.beginTransaction()
                            .replace(R.id.container, EffectsFragment())
                            .addToBackStack("main")
                            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                            .commit()
                    true
                }
                else -> false
            }
        }
        bottomNavigationView.setOnNavigationItemReselectedListener { item ->
            if (item.itemId == R.id.main_art_details) {
                getActivity()?.window?.decorView?.systemUiVisibility = (View.SYSTEM_UI_FLAG_LOW_PROFILE
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
            // Ensure the action bar container gets the appropriate insets
            ViewCompat.dispatchApplyWindowInsets(actionBarContainer,
                    insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft,
                            insets.systemWindowInsetTop,
                            insets.systemWindowInsetRight,
                            0))
            ViewCompat.dispatchApplyWindowInsets(container,
                    insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft,
                            0,
                            insets.systemWindowInsetRight,
                            0))
            ViewCompat.dispatchApplyWindowInsets(bottomNavigationView,
                    insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft,
                            0,
                            insets.systemWindowInsetRight,
                            insets.systemWindowInsetBottom))
            insets
        }

        // Listen for visibility changes to know when to hide our views
        view.setOnSystemUiVisibilityChangeListener { vis ->
            val visible = vis and View.SYSTEM_UI_FLAG_LOW_PROFILE == 0

            actionBarContainer.visibility = View.VISIBLE
            actionBarContainer.animate()
                    .alpha(if (visible) 1f else 0f)
                    .setDuration(200)
                    .withEndAction {
                        if (!visible) {
                            actionBarContainer.visibility = View.GONE
                        }
                    }

            bottomNavigationView.visibility = View.VISIBLE
            bottomNavigationView.animate()
                    .alpha(if (visible) 1f else 0f)
                    .setDuration(200)
                    .withEndAction {
                        if (!visible) {
                            bottomNavigationView.visibility = View.GONE
                        }
                    }
        }
    }

    override fun onRequestCloseActivity() {
        bottomNavigationView.selectedItemId = R.id.main_art_details
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val activity = activity as AppCompatActivity?
        activity?.setSupportActionBar(null)
    }

    override fun onDestroy() {
        childFragmentManager.removeOnBackStackChangedListener(this)
        super.onDestroy()
    }
}
