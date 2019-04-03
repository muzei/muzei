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

package com.google.android.apps.muzei.settings

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import androidx.lifecycle.MutableLiveData
import androidx.viewpager.widget.ViewPager
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.material.tabs.TabLayout
import net.nurik.roman.muzei.R

object EffectsLockScreenOpenLiveData : MutableLiveData<Boolean>()

/**
 * Fragment for allowing the user to configure advanced settings.
 */
class EffectsFragment : Fragment(R.layout.effects_fragment) {

    private lateinit var toolbar: Toolbar
    private lateinit var viewPager: ViewPager

    private val sharedPreferencesListener = SharedPreferences.OnSharedPreferenceChangeListener {
        sp, key ->
        val effectsLinked = sp.getBoolean(Prefs.PREF_LINK_EFFECTS, false)
        if (key == Prefs.PREF_LINK_EFFECTS) {
            if (effectsLinked) {
                if (viewPager.currentItem == 0) {
                    // Update the lock screen effects to match the home screen
                    sp.edit {
                        putInt(Prefs.PREF_LOCK_BLUR_AMOUNT,
                                sp.getInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR))
                        putInt(Prefs.PREF_LOCK_DIM_AMOUNT,
                                sp.getInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM))
                        putInt(Prefs.PREF_LOCK_GREY_AMOUNT,
                                sp.getInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY))
                    }
                } else {
                    // Update the home screen effects to match the lock screen
                    sp.edit {
                        putInt(Prefs.PREF_BLUR_AMOUNT,
                                sp.getInt(Prefs.PREF_LOCK_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR))
                        putInt(Prefs.PREF_DIM_AMOUNT,
                                sp.getInt(Prefs.PREF_LOCK_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM))
                        putInt(Prefs.PREF_GREY_AMOUNT,
                                sp.getInt(Prefs.PREF_LOCK_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY))
                    }
                }
            }
            // Update the menu item
            updateLinkEffectsMenuItem(effectsLinked)
        } else if (effectsLinked) {
            when (key) {
                Prefs.PREF_BLUR_AMOUNT -> {
                    // Update the lock screen effect to match the updated home screen
                    sp.edit {
                        putInt(Prefs.PREF_LOCK_BLUR_AMOUNT, sp.getInt(Prefs.PREF_BLUR_AMOUNT,
                                MuzeiBlurRenderer.DEFAULT_BLUR))
                    }
                }
                Prefs.PREF_DIM_AMOUNT -> {
                    // Update the lock screen effect to match the updated home screen
                    sp.edit {
                        putInt(Prefs.PREF_LOCK_DIM_AMOUNT, sp.getInt(Prefs.PREF_DIM_AMOUNT,
                                MuzeiBlurRenderer.DEFAULT_MAX_DIM))
                    }
                }
                Prefs.PREF_GREY_AMOUNT -> {
                    // Update the lock screen effect to match the updated home screen
                    sp.edit {
                        putInt(Prefs.PREF_LOCK_GREY_AMOUNT, sp.getInt(Prefs.PREF_GREY_AMOUNT,
                                MuzeiBlurRenderer.DEFAULT_GREY))
                    }
                }
                Prefs.PREF_LOCK_BLUR_AMOUNT -> {
                    // Update the home screen effect to match the updated lock screen
                    sp.edit {
                        putInt(Prefs.PREF_BLUR_AMOUNT, sp.getInt(Prefs.PREF_LOCK_BLUR_AMOUNT,
                                MuzeiBlurRenderer.DEFAULT_BLUR))
                    }
                }
                Prefs.PREF_LOCK_DIM_AMOUNT -> {
                    // Update the home screen effect to match the updated lock screen
                    sp.edit {
                        putInt(Prefs.PREF_DIM_AMOUNT, sp.getInt(Prefs.PREF_LOCK_DIM_AMOUNT,
                                MuzeiBlurRenderer.DEFAULT_MAX_DIM))
                    }
                }
                Prefs.PREF_LOCK_GREY_AMOUNT -> {
                    // Update the home screen effect to match the updated lock screen
                    sp.edit {
                        putInt(Prefs.PREF_GREY_AMOUNT, sp.getInt(Prefs.PREF_LOCK_GREY_AMOUNT,
                                MuzeiBlurRenderer.DEFAULT_GREY))
                    }
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        ViewCompat.requestApplyInsets(view)

        toolbar = view.findViewById(R.id.toolbar)
        if (requireActivity() is SettingsActivity) {
            toolbar.setNavigationIcon(R.drawable.ic_ab_done)
            toolbar.navigationContentDescription = getString(R.string.done)
            toolbar.setNavigationOnClickListener {
                requireActivity().run {
                    setResult(Activity.RESULT_OK)
                    finish()
                }
            }
        }
        requireActivity().menuInflater.inflate(R.menu.effects_fragment, toolbar.menu)
        updateLinkEffectsMenuItem()
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_link_effects -> {
                    val sp = Prefs.getSharedPreferences(requireContext())
                    val effectsLinked = sp.getBoolean(Prefs.PREF_LINK_EFFECTS, false)
                    sp.edit {
                        putBoolean(Prefs.PREF_LINK_EFFECTS, !effectsLinked)
                    }
                    true
                }
                R.id.action_reset_defaults -> {
                    Prefs.getSharedPreferences(requireContext()).edit {
                        putInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR)
                        putInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
                        putInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY)
                        putInt(Prefs.PREF_LOCK_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR)
                        putInt(Prefs.PREF_LOCK_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
                        putInt(Prefs.PREF_LOCK_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY)
                    }
                    true
                }
                else -> false
            }
        }
        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)
        viewPager = view.findViewById(R.id.view_pager)
        viewPager.adapter = Adapter(childFragmentManager)
        tabLayout.setupWithViewPager(viewPager)
        viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                EffectsLockScreenOpenLiveData.value = position == 1
            }
        })
        Prefs.getSharedPreferences(requireContext())
                .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
    }

    override fun onStart() {
        super.onStart()
        // Reset the value here to restore the state lost in onStop()
        EffectsLockScreenOpenLiveData.value = viewPager.currentItem == 1
    }

    private fun updateLinkEffectsMenuItem(
            effectsLinked: Boolean = Prefs.getSharedPreferences(requireContext())
                    .getBoolean(Prefs.PREF_LINK_EFFECTS, false)
    ) {
        val menuItem = toolbar.menu.findItem(R.id.action_link_effects)
        menuItem.setIcon(if (effectsLinked)
            R.drawable.ic_action_link_effects
        else
            R.drawable.ic_action_link_effects_off)
        menuItem.title = if (effectsLinked)
            getString(R.string.action_link_effects)
        else
            getString(R.string.action_link_effects_off)
    }

    override fun onStop() {
        // The lock screen effects screen is no longer visible, so set the value to false
        EffectsLockScreenOpenLiveData.value = false
        super.onStop()
    }

    override fun onDestroyView() {
        Prefs.getSharedPreferences(requireContext())
                .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        super.onDestroyView()
    }

    private inner class Adapter(
            fragmentManager: FragmentManager
    ) : FragmentStatePagerAdapter(fragmentManager) {
        override fun getCount() = 2

        override fun getPageTitle(position: Int) = when(position) {
            0 -> getString(R.string.settings_home_screen_title)
            else -> getString(R.string.settings_lock_screen_title)
        }

        override fun getItem(position: Int) = when(position) {
            0 -> EffectsScreenFragment.create(
                    Prefs.PREF_BLUR_AMOUNT,
                    Prefs.PREF_DIM_AMOUNT,
                    Prefs.PREF_GREY_AMOUNT)
            else -> EffectsScreenFragment.create(
                    Prefs.PREF_LOCK_BLUR_AMOUNT,
                    Prefs.PREF_LOCK_DIM_AMOUNT,
                    Prefs.PREF_LOCK_GREY_AMOUNT)
        }
    }
}
