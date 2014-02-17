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

import android.app.AlertDialog;
import android.app.Fragment;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;

import com.google.android.apps.muzei.MusicListenerService;
import com.google.android.apps.muzei.NewWallpaperNotificationReceiver;
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
    private CheckBox mShowMusicArtworkCheckBox;
    private ProgressBar mShowMusicArtworkProgress;

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

        mShowMusicArtworkCheckBox = (CheckBox) rootView.findViewById(
                R.id.show_music_artwork_checkbox);
        mShowMusicArtworkProgress  = (ProgressBar) rootView.findViewById(
                R.id.show_music_artwork_progress);
        if (mShowMusicArtworkCheckBox != null) {
            mShowMusicArtworkCheckBox.setOnCheckedChangeListener(
                    new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton button, boolean checked) {
                            getSharedPreferences().edit()
                                    .putBoolean(MusicListenerService.PREF_ENABLED, checked)
                                    .apply();
                            EventBus.getDefault().post(new BlurAmountChangedEvent());
                            if (checked) {
                                new CheckMusicListenerPermission().execute();
                            }
                        }
                    });
        }
        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mShowMusicArtworkCheckBox != null) {
            boolean showMusicArtwork = getSharedPreferences()
                    .getBoolean(MusicListenerService.PREF_ENABLED, false);
            boolean stateChanging = mShowMusicArtworkCheckBox.isChecked() != showMusicArtwork;
            if (showMusicArtwork && !stateChanging) {
                // Recheck permission if the user hits back from the Settings screen to re-prompt
                // them if they opened Settings but then did not grant permission
                new CheckMusicListenerPermission().execute();
            } else {
                // setChecked will call the permission check when changing the state
                mShowMusicArtworkCheckBox.setChecked(showMusicArtwork);
            }
        }
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

    private class CheckMusicListenerPermission extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected void onPreExecute() {
            mShowMusicArtworkProgress.setVisibility(View.VISIBLE);
        }

        @Override
        protected Boolean doInBackground(final Void... params) {
            ContentResolver contentResolver = getActivity().getContentResolver();
            // This does a direct database call and therefore should not be on the main thread
            final String flattenedListenerList = Settings.Secure.getString(contentResolver,
                    "enabled_notification_listeners");
            if (TextUtils.isEmpty(flattenedListenerList)) {
                return false;
            }
            String ourPackageName = getActivity().getPackageName();
            for (String flatComponentName : flattenedListenerList.split(":")) {
                ComponentName componentName = ComponentName.unflattenFromString(flatComponentName);
                if (componentName != null && TextUtils.equals(componentName.getPackageName(), ourPackageName)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        protected void onPostExecute(final Boolean grantedPermission) {
            mShowMusicArtworkProgress.setVisibility(View.INVISIBLE);
            if (!grantedPermission) {
                new AlertDialog.Builder(getActivity())
                        .setTitle(R.string.settings_enable_music_permission_title)
                        .setMessage(R.string.settings_enable_music_permission_message)
                        .setPositiveButton(R.string.settings_enable_music_permissions_positive,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(final DialogInterface dialog, final int which) {
                                        openSystemSettings();
                                    }
                                })
                        .setNegativeButton(R.string.settings_enable_music_permissions_negative,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(final DialogInterface dialog, final int which) {
                                        cancelEnableMusicPermission();
                                    }
                                })
                        .setOnCancelListener(new DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(final DialogInterface dialog) {
                                cancelEnableMusicPermission();
                            }
                        }).show();
            }
        }

        private void openSystemSettings() {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        }

        private void cancelEnableMusicPermission() {
            mShowMusicArtworkCheckBox.setChecked(false);
            getSharedPreferences().edit()
                    .putBoolean(MusicListenerService.PREF_ENABLED, false)
                    .apply();
            EventBus.getDefault().post(new BlurAmountChangedEvent());
        }
    }
}
