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
import android.app.Notification
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.observe
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

class MuzeiActivity : AppCompatActivity() {
    private var fadeIn = false
    private val viewModel : MuzeiActivityViewModel by viewModels()

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
        if (MissingResourcesDialogFragment.showDialogIfNeeded(this)) {
            return
        }
        setContentView(R.layout.muzei_activity)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        val containerView = findViewById<View>(R.id.container)

        containerView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        if (savedInstanceState == null) {
            WallpaperActiveState.initState(this)
            fadeIn = true
        }

        WallpaperActiveState.observe(this) {
            val fragment = currentFragment
            val oldFragment = supportFragmentManager.findFragmentById(R.id.container)
            if (!fragment::class.java.isInstance(oldFragment)) {
                // Only replace the Fragment if there was a change
                supportFragmentManager.commit {
                    replace(R.id.container, fragment)
                    setPrimaryNavigationFragment(fragment).apply {
                        if (oldFragment != null) {
                            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                        }
                    }
                }
            }
        }

        viewModel.seenTutorialLiveData.observe(this) {
            val fragment = currentFragment
            if (!fragment::class.java.isInstance(
                            supportFragmentManager.findFragmentById(R.id.container))) {
                // Only replace the Fragment if there was a change
                supportFragmentManager.commit(allowStateLoss = true) {
                    replace(R.id.container, fragment)
                    setPrimaryNavigationFragment(fragment)
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                }
            }
        }
        if (intent?.hasCategory(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES) == true) {
            FirebaseAnalytics.getInstance(this).logEvent(
                    "notification_settings_open", bundleOf(
                    FirebaseAnalytics.Param.CONTENT_TYPE to "intent"))
            NotificationSettingsDialogFragment.showSettings(this,
                    supportFragmentManager)
        }
    }

    override fun onPostResume() {
        super.onPostResume()

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

class MuzeiActivityViewModel(application: Application): AndroidViewModel(application) {

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
}
