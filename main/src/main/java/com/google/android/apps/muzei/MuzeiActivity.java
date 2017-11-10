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

package com.google.android.apps.muzei;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class MuzeiActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private boolean mFadeIn = false;
    private boolean mWallpaperActiveStateChanged = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.muzei_activity);
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE);
        final View mContainerView = findViewById(R.id.container);

        mContainerView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        if (savedInstanceState == null) {
            Fragment currentFragment = getCurrentFragment();
            getSupportFragmentManager().beginTransaction()
                    .add(R.id.container, currentFragment)
                    .setPrimaryNavigationFragment(currentFragment)
                    .commit();
            mFadeIn = true;
        }

        EventBus.getDefault().register(this);
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.registerOnSharedPreferenceChangeListener(this);
    }

    private Fragment getCurrentFragment() {
        WallpaperActiveStateChangedEvent e = EventBus.getDefault()
                .getStickyEvent(WallpaperActiveStateChangedEvent.class);
        if (e != null && e.isActive()) {
            final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
            if (sp.getBoolean(TutorialFragment.PREF_SEEN_TUTORIAL, false)) {
                // The wallpaper is active and they've seen the tutorial
                FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Main",
                        MainFragment.class.getSimpleName());
                return new MainFragment();
            } else {
                // They need to see the tutorial after activating Muzei for the first time
                FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Tutorial",
                        TutorialFragment.class.getSimpleName());
                return new TutorialFragment();
            }
        } else {
            // Show the intro fragment to have them activate Muzei
            FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Intro",
                    IntroFragment.class.getSimpleName());
            return new IntroFragment();
        }
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences, final String key) {
        if (TutorialFragment.PREF_SEEN_TUTORIAL.equals(key)) {
            MainFragment fragment = new MainFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, fragment)
                    .setPrimaryNavigationFragment(fragment)
                    .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    .commitAllowingStateLoss();
        }
    }

    @Override
    protected void onDestroy() {
        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        sp.unregisterOnSharedPreferenceChangeListener(this);
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        if (mWallpaperActiveStateChanged) {
            Fragment currentFragment = getCurrentFragment();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.container, currentFragment)
                    .setPrimaryNavigationFragment(currentFragment)
                    .commit();
            mWallpaperActiveStateChanged = false;
        }

        if (mFadeIn) {
            // Note: normally should use window animations for this, but there's a bug
            // on Samsung devices where the wallpaper is animated along with the window for
            // windows showing the wallpaper (the wallpaper _should_ be static, not part of
            // the animation).
            View decorView = getWindow().getDecorView();
            decorView.setAlpha(0f);
            decorView.animate().cancel();
            decorView.animate()
                    .setStartDelay(500)
                    .alpha(1f)
                    .setDuration(300);
            mFadeIn = false;
        }
    }

    @Subscribe
    public void onEventMainThread(final WallpaperActiveStateChangedEvent e) {
        mWallpaperActiveStateChanged = true;
    }
}
