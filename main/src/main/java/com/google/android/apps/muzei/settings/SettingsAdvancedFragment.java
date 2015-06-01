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

import android.app.Activity;
import android.app.Fragment;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;

import com.google.android.apps.muzei.LockScreenVisibleReceiver;
import com.google.android.apps.muzei.MuzeiWallpaperService;
import com.google.android.apps.muzei.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.event.BlurAmountChangedEvent;
import com.google.android.apps.muzei.event.DimAmountChangedEvent;
import com.google.android.apps.muzei.event.DoubleTapActionChangedEvent;
import com.google.android.apps.muzei.event.GreyAmountChangedEvent;
import com.google.android.apps.muzei.event.TapAction;
import com.google.android.apps.muzei.event.ThreeFingerActionChangedEvent;
import com.google.android.apps.muzei.render.MuzeiBlurRenderer;

import net.nurik.roman.muzei.R;

import de.greenrobot.event.EventBus;

/**
 * Fragment for allowing the user to configure advanced settings.
 */
public class SettingsAdvancedFragment extends Fragment
        implements SettingsActivity.SettingsActivityMenuListener {

    private Handler mHandler = new Handler();
    private SeekBar mBlurSeekBar;
    private SeekBar mDimSeekBar;
    private SeekBar mGreySeekBar;
    private CheckBox mNotifyNewWallpaperCheckBox;
    private CheckBox mBlurOnLockScreenCheckBox;
    private Spinner mDoubleTapActionSpinner;
    private Spinner mThreeFingerActionSpinner;

    public SettingsAdvancedFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View rootView = inflater.inflate(R.layout.settings_advanced_fragment, container, false);

        mBlurSeekBar = (SeekBar) rootView.findViewById(R.id.blur_amount);
        mBlurSeekBar.setProgress(getSharedPreferences().getInt(Prefs.PREF_BLUR_AMOUNT,
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
        mDimSeekBar.setProgress(getSharedPreferences().getInt(Prefs.PREF_DIM_AMOUNT,
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

        mGreySeekBar = (SeekBar) rootView.findViewById(R.id.grey_amount);
        mGreySeekBar.setProgress(getSharedPreferences().getInt(Prefs.PREF_GREY_AMOUNT,
                MuzeiBlurRenderer.DEFAULT_GREY));
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

        //Double Tap action

        mDoubleTapActionSpinner = (Spinner)rootView.findViewById(R.id.advanced_settings_doubletapaction_spinner);

        TapActionEntry[] entries = new TapActionEntry[] {
                new TapActionEntry(getString(R.string.settings_tap_action_nothing), TapAction.NOTHING),
                new TapActionEntry(getString(R.string.settings_tap_action_showoriginal), TapAction.SHOW_ORIGINAL_ARTWORK),
                new TapActionEntry(getString(R.string.settings_tap_action_next), TapAction.NEXT_ARTWORK),
                new TapActionEntry(getString(R.string.settings_tap_action_view), TapAction.VIEW_ARTWORK)
        };

        ArrayAdapter<TapActionEntry> doubleTapActionSpinnerAdapter =
                new ArrayAdapter<TapActionEntry>(
                        inflater.getContext(),
                        R.layout.settings_ab_spinner_list_item_dropdown,
                        entries);

        @TapAction.Value
        int currentDoubleTapAction = getSharedPreferences().getInt(
                MuzeiWallpaperService.PREF_DOUBLETAPACTION,
                TapAction.SHOW_ORIGINAL_ARTWORK);

        initTapActionSpinner(mDoubleTapActionSpinner, doubleTapActionSpinnerAdapter, entries, currentDoubleTapAction);

        mDoubleTapActionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                TapActionEntry entry = (TapActionEntry)parent.getSelectedItem();
                @TapAction.Value int newAction = entry.getTapAction();
                getSharedPreferences().edit()
                        .putInt(MuzeiWallpaperService.PREF_DOUBLETAPACTION, newAction)
                        .apply();
                EventBus.getDefault().post(new DoubleTapActionChangedEvent(newAction));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        //Three finger action

        mThreeFingerActionSpinner = (Spinner)rootView.findViewById(R.id.advanced_settings_threefingertapaction_spinner);

        ArrayAdapter<TapActionEntry> threeFingerActionSpinnerAdapter =
                new ArrayAdapter<TapActionEntry>(
                        inflater.getContext(),
                        R.layout.settings_ab_spinner_list_item_dropdown,
                        entries);

        @TapAction.Value
        int currentThreeFingerAction = getSharedPreferences().getInt(
                MuzeiWallpaperService.PREF_THREEFINGERACTION,
                TapAction.NOTHING);

        initTapActionSpinner(mThreeFingerActionSpinner, threeFingerActionSpinnerAdapter, entries, currentThreeFingerAction);

        mThreeFingerActionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                TapActionEntry entry = (TapActionEntry)parent.getSelectedItem();
                @TapAction.Value
                int newAction = entry.getTapAction();
                getSharedPreferences().edit()
                        .putInt(MuzeiWallpaperService.PREF_THREEFINGERACTION, newAction)
                        .apply();
                EventBus.getDefault().post(new ThreeFingerActionChangedEvent(newAction));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
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
                }
        );
        mNotifyNewWallpaperCheckBox.setChecked(getSharedPreferences()
                .getBoolean(NewWallpaperNotificationReceiver.PREF_ENABLED, true));

        mBlurOnLockScreenCheckBox = (CheckBox) rootView.findViewById(
                R.id.blur_on_lockscreen_checkbox);
        mBlurOnLockScreenCheckBox.setOnCheckedChangeListener(
                new CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        getSharedPreferences().edit()
                                .putBoolean(LockScreenVisibleReceiver.PREF_ENABLED, !checked)
                                .apply();
                    }
                }
        );
        mBlurOnLockScreenCheckBox.setChecked(!getSharedPreferences()
                .getBoolean(LockScreenVisibleReceiver.PREF_ENABLED, false));
        return rootView;
    }

    private void initTapActionSpinner(Spinner tapActionSpinner, ArrayAdapter<TapActionEntry> arrayAdapter, TapActionEntry[] entries, @TapAction.Value int currentTapAction) {
        tapActionSpinner.setAdapter(arrayAdapter);

        //Search the current action in the provided entries
        int idx = 0;
        for(int i=0; i<entries.length; i++)
        {
            if(entries[i].getTapAction() == currentTapAction)
            {
                idx = i;
                break;
            }
        }
        //to set the selected item
        tapActionSpinner.setSelection(idx);
    }

    @Override
    public void onAttach(Activity activity) {
        ((SettingsActivity) activity).inflateMenuFromFragment(R.menu.settings_advanced);
        super.onAttach(activity);
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
                    .putInt(Prefs.PREF_BLUR_AMOUNT, mBlurSeekBar.getProgress())
                    .apply();
            EventBus.getDefault().post(new BlurAmountChangedEvent());
        }
    };

    private Runnable mUpdateDimRunnable = new Runnable() {
        @Override
        public void run() {
            getSharedPreferences().edit()
                    .putInt(Prefs.PREF_DIM_AMOUNT, mDimSeekBar.getProgress())
                    .apply();
            EventBus.getDefault().post(new DimAmountChangedEvent());
        }
    };

    private Runnable mUpdateGreyRunnable = new Runnable() {
        @Override
        public void run() {
            getSharedPreferences().edit()
                    .putInt(Prefs.PREF_GREY_AMOUNT, mGreySeekBar.getProgress())
                    .apply();
            EventBus.getDefault().post(new GreyAmountChangedEvent());
        }
    };

    @Override
    public void onSettingsActivityMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.action_reset_defaults) {
            getSharedPreferences().edit()
                    .putInt(Prefs.PREF_BLUR_AMOUNT, MuzeiBlurRenderer.DEFAULT_BLUR)
                    .putInt(Prefs.PREF_DIM_AMOUNT, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
                    .putInt(Prefs.PREF_GREY_AMOUNT, MuzeiBlurRenderer.DEFAULT_GREY)
                    .apply();
            mBlurSeekBar.setProgress(MuzeiBlurRenderer.DEFAULT_BLUR);
            mDimSeekBar.setProgress(MuzeiBlurRenderer.DEFAULT_MAX_DIM);
            mGreySeekBar.setProgress(MuzeiBlurRenderer.DEFAULT_GREY);
            EventBus.getDefault().post(new BlurAmountChangedEvent());
            EventBus.getDefault().post(new DimAmountChangedEvent());
            EventBus.getDefault().post(new GreyAmountChangedEvent());
        }
    }

    public class TapActionEntry
    {
        private String mName;
        @TapAction.Value
        private int mAction;

        public TapActionEntry(String name, @TapAction.Value int action)
        {
            mName = name;
            mAction = action;
        }

        public String getName()
        {
            return mName;
        }

        @TapAction.Value
        public int getTapAction()
        {
            return mAction;
        }

        @Override
        public String toString() {
            return mName;
        }
    }
}
