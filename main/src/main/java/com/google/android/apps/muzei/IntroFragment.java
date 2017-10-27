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

import android.app.WallpaperManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.R;

public class IntroFragment extends Fragment {
    private View mActivateButton;

    public IntroFragment() {
    }

    @Override
    public void onCreate(@Nullable final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            FirebaseAnalytics.getInstance(getContext()).logEvent(FirebaseAnalytics.Event.TUTORIAL_BEGIN, null);
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.intro_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull final View view, @Nullable final Bundle savedInstanceState) {
        mActivateButton = view.findViewById(R.id.activate_muzei_button);
        mActivateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FirebaseAnalytics.getInstance(getContext()).logEvent("activate", null);
                try {
                    startActivity(new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                    new ComponentName(getContext(),
                                            MuzeiWallpaperService.class))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (ActivityNotFoundException e) {
                    try {
                        startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException e2) {
                        Toast.makeText(getContext(), R.string.error_wallpaper_chooser,
                                Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
    }

    @Override
    public void onActivityCreated(@Nullable final Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        if (savedInstanceState == null) {
            final AnimatedMuzeiLogoFragment logoFragment = new AnimatedMuzeiLogoFragment();
            getChildFragmentManager().beginTransaction()
                    .add(R.id.animated_logo_fragment, logoFragment)
                    .commitNow();

            mActivateButton.setAlpha(0);
            logoFragment.setOnFillStartedCallback(new Runnable() {
                @Override
                public void run() {
                    mActivateButton.animate().alpha(1f).setDuration(500);
                }
            });
            mActivateButton.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (logoFragment.isAdded()) {
                        logoFragment.start();
                    }
                }
            }, 1000);
        }
    }
}
