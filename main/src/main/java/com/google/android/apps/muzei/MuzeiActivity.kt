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

package com.google.android.apps.muzei

import android.app.Application
import android.app.WallpaperManager
import android.arch.lifecycle.AndroidViewModel
import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.support.v4.app.Fragment
import android.support.v4.app.FragmentTransaction
import android.support.v7.app.AppCompatActivity
import android.view.View
import com.google.android.apps.muzei.util.observe
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

class MuzeiActivity : AppCompatActivity() {
    private var fadeIn = false
    private val viewModel : MuzeiActivityViewModel by lazy {
        val viewModelProvider = ViewModelProvider(this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application))
        viewModelProvider[MuzeiActivityViewModel::class.java]
    }

    private val currentFragment: Fragment
        get() {
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            val seenTutorial = sp.getBoolean(TutorialFragment.PREF_SEEN_TUTORIAL, false)
            when {
                WallpaperActiveState.value == true && seenTutorial -> {
                    // The wallpaper is active and they've seen the tutorial
                    FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Main",
                            MainFragment::class.java.simpleName)
                    return MainFragment()
                }
                WallpaperActiveState.value == true && !seenTutorial -> {
                    // They need to see the tutorial after activating Muzei for the first time
                    FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Tutorial",
                            TutorialFragment::class.java.simpleName)
                    return TutorialFragment()
                }
                else -> {
                    // Show the intro fragment to have them activate Muzei
                    FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Intro",
                            IntroFragment::class.java.simpleName)
                    return IntroFragment()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.muzei_activity)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        val containerView = findViewById<View>(R.id.container)

        containerView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        if (savedInstanceState == null) {
            if (WallpaperActiveState.value != true) {
                // Double check to make sure we aren't just in the processing of being started
                val wallpaperManager = WallpaperManager.getInstance(this)
                if (wallpaperManager.wallpaperInfo?.packageName == packageName) {
                    // Ah, we are the active wallpaper. We'll just mark ourselves as active here
                    // to skip the Intro screen
                    WallpaperActiveState.value = true
                }
            }
            val currentFragment = currentFragment
            supportFragmentManager.beginTransaction()
                    .add(R.id.container, currentFragment)
                    .setPrimaryNavigationFragment(currentFragment)
                    .commit()
            fadeIn = true
        }

        viewModel.seenTutorialLiveData.observe(this) {
            val fragment = MainFragment()
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, fragment)
                    .setPrimaryNavigationFragment(fragment)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .commitAllowingStateLoss()
        }
    }

    override fun onPostResume() {
        super.onPostResume()

        if (viewModel.wallpaperActiveStateChanged) {
            val currentFragment = currentFragment
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, currentFragment)
                    .setPrimaryNavigationFragment(currentFragment)
                    .commit()
            viewModel.wallpaperActiveStateChanged = false
        }

        if (fadeIn) {
            // Note: normally should use window animations for this, but there's a bug
            // on Samsung devices where the wallpaper is animated along with the window for
            // windows showing the wallpaper (the wallpaper _should_ be static, not part of
            // the animation).
            window.decorView.run {
                alpha = 0f
                animate().cancel()
                animate().setStartDelay(500).alpha(1f).duration = 300
            }

            fadeIn = false
        }
    }
}

class MuzeiActivityViewModel(
        application: Application
): AndroidViewModel(application), Observer<Boolean> {

    internal val seenTutorialLiveData : LiveData<Boolean> = object : MutableLiveData<Boolean>(),
            SharedPreferences.OnSharedPreferenceChangeListener {
        val sp = PreferenceManager.getDefaultSharedPreferences(application)

        override fun onActive() {
            sp.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
            if (TutorialFragment.PREF_SEEN_TUTORIAL == key) {
                value = sp.getBoolean(TutorialFragment.PREF_SEEN_TUTORIAL, false)
            }
        }

        override fun onInactive() {
            sp.unregisterOnSharedPreferenceChangeListener(this)
        }
    }

    internal var wallpaperActiveStateChanged = false
    private var currentState = WallpaperActiveState.value

    init {
        // Use observeForever to continue to listen even after we're stopped
        // as is the case when the wallpaper chooser is displayed over us
        WallpaperActiveState.observeForever(this)
    }

    override fun onChanged(newState: Boolean?) {
        if (currentState != newState) {
            wallpaperActiveStateChanged = true
        }
    }

    override fun onCleared() {
        WallpaperActiveState.removeObserver(this)
    }
}
