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

package com.google.android.apps.muzei.settings;

import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;

import com.google.android.apps.muzei.render.MuzeiBlurRenderer;

import net.nurik.roman.muzei.R;

/**
 * Fragment for allowing the user to configure advanced settings.
 */
public class EffectsFragment extends Fragment {

    private Handler mHandler = new Handler();
    private SeekBar mBlurSeekBar;
    private SeekBar mDimSeekBar;
    private SeekBar mGreySeekBar;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.settings_advanced_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        // Ensure we have the latest insets
        view.requestFitSystemWindows();

        mBlurSeekBar = view.findViewById(R.id.blur_amount);
        mBlurSeekBar.setProgress(Prefs.getSharedPreferences(getContext())
                .getInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR));
        mBlurSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    mHandler.removeCallbacks(mUpdateBlurRunnable);
                    mHandler.postDelayed(mUpdateBlurRunnable, 750);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mDimSeekBar = view.findViewById(R.id.dim_amount);
        mDimSeekBar.setProgress(Prefs.getSharedPreferences(getContext())
                .getInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM));
        mDimSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    mHandler.removeCallbacks(mUpdateDimRunnable);
                    mHandler.postDelayed(mUpdateDimRunnable, 750);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        mGreySeekBar = view.findViewById(R.id.grey_amount);
        mGreySeekBar.setProgress(Prefs.getSharedPreferences(getContext())
                .getInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY));
        mGreySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int value, boolean fromUser) {
                if (fromUser) {
                    mHandler.removeCallbacks(mUpdateGreyRunnable);
                    mHandler.postDelayed(mUpdateGreyRunnable, 750);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        CheckBox mBlurOnLockScreenCheckBox = view.findViewById(
                R.id.blur_on_lockscreen_checkbox);
        mBlurOnLockScreenCheckBox.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        Prefs.getSharedPreferences(getContext()).edit()
                                .putBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, !checked)
                                .apply();
                    }
                }
        );
        mBlurOnLockScreenCheckBox.setChecked(!Prefs.getSharedPreferences(getContext())
                .getBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, false));
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.settings_advanced, menu);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private Runnable mUpdateBlurRunnable = new Runnable() {
        @Override
        public void run() {
            Prefs.getSharedPreferences(getContext()).edit()
                    .putInt(Prefs.PREF_BLUR_AMOUNT, mBlurSeekBar.getProgress())
                    .apply();
        }
    };

    private Runnable mUpdateDimRunnable = new Runnable() {
        @Override
        public void run() {
            Prefs.getSharedPreferences(getContext()).edit()
                    .putInt(Prefs.PREF_DIM_AMOUNT, mDimSeekBar.getProgress())
                    .apply();
        }
    };

    private Runnable mUpdateGreyRunnable = new Runnable() {
        @Override
        public void run() {
            Prefs.getSharedPreferences(getContext()).edit()
                    .putInt(Prefs.PREF_GREY_AMOUNT, mGreySeekBar.getProgress())
                    .apply();
        }
    };

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_reset_defaults:
                Prefs.getSharedPreferences(getContext()).edit()
                        .putInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR)
                        .putInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
                        .putInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY)
                        .apply();
                mBlurSeekBar.setProgress(MuzeiBlurRenderer.DEFAULT_BLUR);
                mDimSeekBar.setProgress(MuzeiBlurRenderer.DEFAULT_MAX_DIM);
                mGreySeekBar.setProgress(MuzeiBlurRenderer.DEFAULT_GREY);
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
