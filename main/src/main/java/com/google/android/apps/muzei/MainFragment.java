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

package com.google.android.apps.muzei;

import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.BottomNavigationView;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.view.OnApplyWindowInsetsListener;
import android.support.v4.view.ViewCompat;
import android.support.v4.view.WindowInsetsCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import com.google.android.apps.muzei.settings.EffectsFragment;
import com.google.android.apps.muzei.settings.ChooseSourceFragment;
import com.google.android.apps.muzei.util.ScrimUtil;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

/**
 * Fragment which controls the main view of the Muzei app and handles the bottom navigation
 * between various screens.
 */
public class MainFragment extends Fragment implements FragmentManager.OnBackStackChangedListener,
        ChooseSourceFragment.Callbacks {
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getChildFragmentManager().addOnBackStackChangedListener(this);
    }

    @Override
    public void onBackStackChanged() {
        if (getChildFragmentManager().getBackStackEntryCount() == 0) {
            BottomNavigationView bottomNavigationView = getView().findViewById(R.id.bottom_nav);
            bottomNavigationView.setSelectedItemId(R.id.main_art_details);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container, @Nullable final Bundle savedInstanceState) {
        return inflater.inflate(R.layout.main_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        // Set up the action bar
        final View actionBarContainer = view.findViewById(R.id.action_bar_container);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            actionBarContainer.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                    0x44000000, 8, Gravity.TOP));
        }
        final Toolbar toolbar = view.findViewById(R.id.toolbar);
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null) {
            activity.setSupportActionBar(toolbar);
            activity.getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // Set up the container for the child fragments
        final View container = view.findViewById(R.id.container);
        if (savedInstanceState == null) {
            FirebaseAnalytics.getInstance(getActivity())
                    .setCurrentScreen(getActivity(), "ArtDetail",
                            ArtDetailFragment.class.getSimpleName());
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.container, new ArtDetailFragment())
                    .commit();
        }

        // Set up the bottom nav
        final BottomNavigationView bottomNavigationView = view.findViewById(R.id.bottom_nav);
        bottomNavigationView.setOnNavigationItemSelectedListener(
                new BottomNavigationView.OnNavigationItemSelectedListener() {
                    @Override
                    public boolean onNavigationItemSelected(@NonNull final MenuItem item) {
                        if (getChildFragmentManager().isStateSaved()) {
                            // Can't navigate after the state is saved
                            return false;
                        }
                        switch (item.getItemId()) {
                            case R.id.main_art_details:
                                FirebaseAnalytics.getInstance(getContext())
                                        .setCurrentScreen(getActivity(), "ArtDetail",
                                                ArtDetailFragment.class.getSimpleName());
                                getChildFragmentManager().beginTransaction()
                                        .replace(R.id.container, new ArtDetailFragment())
                                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                                        .commit();
                                return true;
                            case R.id.main_choose_source:
                                FirebaseAnalytics.getInstance(getContext())
                                        .setCurrentScreen(getActivity(), "ChooseSource",
                                                ChooseSourceFragment.class.getSimpleName());
                                getChildFragmentManager().popBackStack("main",
                                        FragmentManager.POP_BACK_STACK_INCLUSIVE);
                                getChildFragmentManager().beginTransaction()
                                        .replace(R.id.container, new ChooseSourceFragment())
                                        .addToBackStack("main")
                                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                                        .commit();
                                return true;
                            case R.id.main_effects:
                                FirebaseAnalytics.getInstance(getContext())
                                        .setCurrentScreen(getActivity(), "Effects",
                                                EffectsFragment.class.getSimpleName());
                                getChildFragmentManager().popBackStack("main",
                                        FragmentManager.POP_BACK_STACK_INCLUSIVE);
                                getChildFragmentManager().beginTransaction()
                                        .replace(R.id.container, new EffectsFragment())
                                        .addToBackStack("main")
                                        .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                                        .commit();
                                return true;
                            default:
                                return false;
                        }
                    }
                });
        bottomNavigationView.setOnNavigationItemReselectedListener(
                new BottomNavigationView.OnNavigationItemReselectedListener() {
                    @Override
                    public void onNavigationItemReselected(@NonNull final MenuItem item) {
                        if (item.getItemId() == R.id.main_art_details) {
                            getActivity().getWindow().getDecorView()
                                    .setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                                            | View.SYSTEM_UI_FLAG_IMMERSIVE
                                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                        }
                    }
                });

        // Send the correct window insets to each view
        ViewCompat.setOnApplyWindowInsetsListener(view, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(final View v, final WindowInsetsCompat insets) {
                // Ensure the action bar container gets the appropriate insets
                ViewCompat.dispatchApplyWindowInsets(actionBarContainer,
                        insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(),
                                insets.getSystemWindowInsetTop(),
                                insets.getSystemWindowInsetRight(),
                                0));
                ViewCompat.dispatchApplyWindowInsets(container,
                        insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(),
                                0,
                                insets.getSystemWindowInsetRight(),
                                0));
                ViewCompat.dispatchApplyWindowInsets(bottomNavigationView,
                        insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(),
                                0,
                                insets.getSystemWindowInsetRight(),
                                insets.getSystemWindowInsetBottom()));
                return insets;
            }
        });

        // Listen for visibility changes to know when to hide our views
        view.setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int vis) {
                        final boolean visible = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0;

                        actionBarContainer.setVisibility(View.VISIBLE);
                        actionBarContainer.animate()
                                .alpha(visible ? 1f : 0f)
                                .setDuration(200)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!visible) {
                                            actionBarContainer.setVisibility(View.GONE);
                                        }
                                    }
                                });

                        bottomNavigationView.setVisibility(View.VISIBLE);
                        bottomNavigationView.animate()
                                .alpha(visible ? 1f : 0f)
                                .setDuration(200)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!visible) {
                                            bottomNavigationView.setVisibility(View.GONE);
                                        }
                                    }
                                });
                    }
                });

    }

    @Override
    public void onRequestCloseActivity() {
        BottomNavigationView bottomNavigationView = getView().findViewById(R.id.bottom_nav);
        bottomNavigationView.setSelectedItemId(R.id.main_art_details);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity != null) {
            activity.setSupportActionBar(null);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getChildFragmentManager().removeOnBackStackChangedListener(this);
    }
}
