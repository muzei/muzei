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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.SeekBar;
import android.widget.Spinner;

import com.google.android.apps.muzei.MuzeiWallpaperService;
import com.google.android.apps.muzei.NewWallpaperNotificationReceiver;
import com.google.android.apps.muzei.event.BlurAmountChangedEvent;
import com.google.android.apps.muzei.event.DimAmountChangedEvent;
import com.google.android.apps.muzei.event.DoubleTapActionChangedEvent;
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
    private Spinner mDoubleTapActionSpinner;

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

        //Double Tap action

        mDoubleTapActionSpinner = (Spinner)rootView.findViewById(R.id.advanced_settings_doubletapaction_spinner);

        DoubleTapActionEntry[] entries = new DoubleTapActionEntry[] {
            new DoubleTapActionEntry(getString(R.string.settings_doubletap_action_deblur), DoubleTapActionChangedEvent.DoubleTapAction.Deblur),
            new DoubleTapActionEntry(getString(R.string.settings_doubletap_action_next), DoubleTapActionChangedEvent.DoubleTapAction.NextItem)
        };

        ArrayAdapter<DoubleTapActionEntry> doubleTapActionSpinnerAdapter =
                new ArrayAdapter<DoubleTapActionEntry>(
                        inflater.getContext(),
                        android.R.layout.simple_spinner_dropdown_item,
                        entries);

        mDoubleTapActionSpinner.setAdapter(doubleTapActionSpinnerAdapter);
        mDoubleTapActionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                DoubleTapActionEntry entry = (DoubleTapActionEntry)parent.getSelectedItem();
                DoubleTapActionChangedEvent.DoubleTapAction newAction = entry.getDoubleTapAction();
                getSharedPreferences().edit()
                        .putInt(MuzeiWallpaperService.PREF_DOUBLETAPACTION, newAction.getAction())
                        .apply();
                EventBus.getDefault().post(new DoubleTapActionChangedEvent(newAction));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        DoubleTapActionChangedEvent.DoubleTapAction currentDoubleTapAction = DoubleTapActionChangedEvent.DoubleTapAction.fromAction(
                getSharedPreferences().getInt(
                        MuzeiWallpaperService.PREF_DOUBLETAPACTION,
                        DoubleTapActionChangedEvent.DoubleTapAction.Deblur.getAction()));

        //Search the current action in the provided entries
        int idx = 0;
        for(int i=0; i<entries.length; i++)
        {
            if(entries[i].getDoubleTapAction() == currentDoubleTapAction)
            {
                idx = i;
                break;
            }
        }
        //to set the selected item
        mDoubleTapActionSpinner.setSelection(idx);

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

    public class DoubleTapActionEntry
    {
        private String mName;
        private DoubleTapActionChangedEvent.DoubleTapAction mAction;

        public DoubleTapActionEntry(String name, DoubleTapActionChangedEvent.DoubleTapAction action)
        {
            mName = name;
            mAction = action;
        }

        public String getName()
        {
            return mName;
        }

        public DoubleTapActionChangedEvent.DoubleTapAction getDoubleTapAction()
        {
            return mAction;
        }

        @Override
        public String toString() {
            return mName;
        }
    }
}
