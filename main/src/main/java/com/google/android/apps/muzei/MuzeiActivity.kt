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

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.AndroidViewModel
import androidx.preference.PreferenceManager
import com.google.android.apps.muzei.notifications.NotificationSettingsDialogFragment
import com.google.android.apps.muzei.render.MuzeiRendererFragment
import com.google.android.apps.muzei.settings.EffectsFragment
import com.google.android.apps.muzei.util.launchWhenStartedIn
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.android.apps.muzei.wallpaper.initializeWallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.sendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onEach
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiActivityBinding

private const val PREVIEW_MODE = "android.service.wallpaper.PREVIEW_MODE"
val Activity.isPreviewMode get() = intent?.extras?.getBoolean(PREVIEW_MODE) == true

@OptIn(ExperimentalCoroutinesApi::class)
class MuzeiActivity : AppCompatActivity() {
    private lateinit var binding: MuzeiActivityBinding
    private var fadeIn = false
    private var renderLocally = false
    private val viewModel : MuzeiActivityViewModel by viewModels()

    private val currentFragment: Fragment
        get() {
            val sp = PreferenceManager.getDefaultSharedPreferences(this)
            val seenTutorial = sp.getBoolean(TutorialFragment.PREF_SEEN_TUTORIAL, false)
            return when {
                WallpaperActiveState.value && seenTutorial -> {
                    // The wallpaper is active and they've seen the tutorial
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "Main")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                MainFragment::class.java.simpleName)
                    }
                    MainFragment()
                }
                WallpaperActiveState.value && !seenTutorial -> {
                    // They need to see the tutorial after activating Muzei for the first time
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "Tutorial")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                TutorialFragment::class.java.simpleName)
                    }
                    TutorialFragment()
                }
                isPreviewMode -> {
                    // We're previewing the wallpaper and want to adjust its settings
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "Effects")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                EffectsFragment::class.java.simpleName)
                    }
                    EffectsFragment()
                }
                else -> {
                    // Show the intro fragment to have them activate Muzei
                    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                        param(FirebaseAnalytics.Param.SCREEN_NAME, "Intro")
                        param(FirebaseAnalytics.Param.SCREEN_CLASS,
                                IntroFragment::class.java.simpleName)
                    }
                    IntroFragment()
                }
            }.also {
                updateRenderLocally(it is EffectsFragment)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MissingResourcesDialogFragment.showDialogIfNeeded(this)) {
            return
        }
        binding = MuzeiActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Firebase.analytics.setUserProperty("device_type", BuildConfig.DEVICE_TYPE)

        binding.container.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)

        if (savedInstanceState == null) {
            initializeWallpaperActiveState(this)
            fadeIn = true
        }

        WallpaperActiveState.onEach {
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
        }.launchWhenStartedIn(this)

        viewModel.onSeenTutorial().onEach {
            val fragment = currentFragment
            if (!fragment::class.java.isInstance(
                            supportFragmentManager.findFragmentById(R.id.container))) {
                // Only replace the Fragment if there was a change
                supportFragmentManager.commit {
                    replace(R.id.container, fragment)
                    setPrimaryNavigationFragment(fragment)
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                }
            }
        }.launchWhenStartedIn(this)
        if (intent?.hasCategory(Notification.INTENT_CATEGORY_NOTIFICATION_PREFERENCES) == true) {
            Firebase.analytics.logEvent("notification_settings_open") {
                param(FirebaseAnalytics.Param.CONTENT_TYPE, "intent")
            }
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

    private fun updateRenderLocally(renderLocally: Boolean) {
        if (this.renderLocally == renderLocally) {
            return
        }

        this.renderLocally = renderLocally

        val fm = supportFragmentManager
        val localRenderFragment = fm.findFragmentById(R.id.local_render_container)
        if (renderLocally) {
            if (localRenderFragment == null) {
                fm.commit {
                    add(R.id.local_render_container,
                            MuzeiRendererFragment.createInstance(false))
                }
            }
        } else {
            if (localRenderFragment != null) {
                fm.commit {
                    remove(localRenderFragment)
                }
            }
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MuzeiActivityViewModel(application: Application): AndroidViewModel(application) {
    private val sp: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)

    fun onSeenTutorial(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (TutorialFragment.PREF_SEEN_TUTORIAL == key) {
                sendBlocking(sp.getBoolean(TutorialFragment.PREF_SEEN_TUTORIAL, false))
            }
        }
        sp.registerOnSharedPreferenceChangeListener(listener)

        awaitClose {
            sp.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
