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
import android.arch.lifecycle.MutableLiveData
import android.os.Bundle
import android.support.design.widget.TabLayout
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentManager
import android.support.v4.app.FragmentStatePagerAdapter
import android.support.v4.view.ViewPager
import android.support.v7.widget.Toolbar
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import net.nurik.roman.muzei.R

object EffectsLockScreenOpenLiveData : MutableLiveData<Boolean>()

/**
 * Fragment for allowing the user to configure advanced settings.
 */
class EffectsFragment : Fragment() {

    private lateinit var viewPager: ViewPager

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.effects_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Ensure we have the latest insets
        @Suppress("DEPRECATION")
        view.requestFitSystemWindows()

        val toolbar: Toolbar = view.findViewById(R.id.toolbar)
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
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
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
    }

    override fun onDestroyView() {
        EffectsLockScreenOpenLiveData.value = false
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
