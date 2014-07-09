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

import android.app.Fragment;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;

import com.google.android.apps.muzei.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.UserPresentReceiver;
import com.google.android.apps.muzei.event.BlurAmountChangedEvent;
import com.google.android.apps.muzei.event.DimAmountChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;

import net.nurik.roman.muzei.R;

import de.greenrobot.event.EventBus;

/**
 * Fragment for allowing the user to configure advanced settings.
 */
public class SettingsAdvancedFragment extends Fragment {
    private Handler mHandler = new Handler();
    private SeekBar mBlurSeekBar;
    private SeekBar mDimSeekBar;
    private CheckBox mNotifyNewWallpaperCheckBox;
    private CheckBox mDisableBlurWhenScreenLockedCheckBox;

    public SettingsAdvancedFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.settings_advanced_fragment, container, false);

        mBlurSeekBar = (SeekBar) rootView.findViewById(R.id.blur_amount);
        mBlurSeekBar.setProgress(getSharedPreferences().getInt("blur_amount",
                MuzeiBlurRenderer.DEFAULT_BLUR));
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

        mDimSeekBar = (SeekBar) rootView.findViewById(R.id.dim_amount);
        mDimSeekBar.setProgress(getSharedPreferences().getInt("dim_amount",
                MuzeiBlurRenderer.DEFAULT_MAX_DIM));
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

        mNotifyNewWallpaperCheckBox = (CheckBox) rootView.findViewById(
                R.id.notify_new_wallpaper_checkbox);
        mNotifyNewWallpaperCheckBox.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        getSharedPreferences().edit()
                                .putBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, checked)
                                .apply();
                        EventBus.getDefault().post(new BlurAmountChangedEvent());

                    }
                });
        mNotifyNewWallpaperCheckBox.setChecked(getSharedPreferences()
                .getBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, true));

        mDisableBlurWhenScreenLockedCheckBox = (CheckBox) rootView.findViewById(
                R.id.disable_blur_when_locked_checkbox);
        mDisableBlurWhenScreenLockedCheckBox.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        getSharedPreferences().edit()
                                .putBoolean(UserPresentReceiver.PREF_ENABLED, checked)
                                .apply();
                        EventBus.getDefault().post(new BlurAmountChangedEvent());
                    }
                }
        );
        mDisableBlurWhenScreenLockedCheckBox.setChecked(getSharedPreferences()
                .getBoolean(UserPresentReceiver.PREF_ENABLED, true));
        return rootView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private SharedPreferences getSharedPreferences() {
        return PreferenceManager.getDefaultSharedPreferences(getActivity());
    }

    private Runnable mUpdateBlurRunnable = new Runnable() {
        @Override
        public void run() {
            getSharedPreferences().edit()
                    .putInt("blur_amount", mBlurSeekBar.getProgress())
                    .apply();
            EventBus.getDefault().post(new BlurAmountChangedEvent());
        }
    };

    private Runnable mUpdateDimRunnable = new Runnable() {
        @Override
        public void run() {
            getSharedPreferences().edit()
                    .putInt("dim_amount", mDimSeekBar.getProgress())
                    .apply();
            EventBus.getDefault().post(new DimAmountChangedEvent());
        }
    };
}
