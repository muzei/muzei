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

package com.google.android.apps.muzei.wallpaper;

import android.app.KeyguardManager;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.support.v4.os.UserManagerCompat;

import com.google.android.apps.muzei.MuzeiWallpaperService;
import com.google.android.apps.muzei.settings.Prefs;

/**
 * LifecycleObserver responsible for monitoring the state of the lock screen
 */
public class LockscreenObserver implements LifecycleObserver {
    private final Context mContext;
    private final MuzeiWallpaperService.MuzeiWallpaperEngine mEngine;

    private boolean mIsLockScreenVisibleReceiverRegistered = false;
    private SharedPreferences.OnSharedPreferenceChangeListener
            mLockScreenPreferenceChangeListener = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(final SharedPreferences sp, final String key) {
            if (Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED.equals(key)) {
                if (sp.getBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, false)) {
                    IntentFilter intentFilter = new IntentFilter();
                    intentFilter.addAction(Intent.ACTION_USER_PRESENT);
                    intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
                    intentFilter.addAction(Intent.ACTION_SCREEN_ON);
                    mContext.registerReceiver(mLockScreenVisibleReceiver, intentFilter);
                    mIsLockScreenVisibleReceiverRegistered = true;
                    // If the user is not yet unlocked (i.e., using Direct Boot), we should
                    // immediately send the lock screen visible callback
                    if (!UserManagerCompat.isUserUnlocked(mContext)) {
                        mEngine.lockScreenVisibleChanged(true);
                    }
                } else if (mIsLockScreenVisibleReceiverRegistered) {
                    mContext.unregisterReceiver(mLockScreenVisibleReceiver);
                    mIsLockScreenVisibleReceiverRegistered = false;
                }
            }
        }
    };
    private BroadcastReceiver mLockScreenVisibleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            if (intent != null) {
                if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    mEngine.lockScreenVisibleChanged(false);
                } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    mEngine.lockScreenVisibleChanged(true);
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    KeyguardManager kgm = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    if (!kgm.inKeyguardRestrictedInputMode()) {
                        mEngine.lockScreenVisibleChanged(false);
                    }
                }
            }
        }
    };

    public LockscreenObserver(Context context, MuzeiWallpaperService.MuzeiWallpaperEngine engine) {
        mContext = context;
        mEngine = engine;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void registerOnSharedPreferenceChangeListener() {
        SharedPreferences sp = Prefs.getSharedPreferences(mContext);
        sp.registerOnSharedPreferenceChangeListener(mLockScreenPreferenceChangeListener);
        // Trigger the initial registration if needed
        mLockScreenPreferenceChangeListener.onSharedPreferenceChanged(sp,
                Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void unregisterOnSharedPreferenceChangeListener() {
        if (mIsLockScreenVisibleReceiverRegistered) {
            mContext.unregisterReceiver(mLockScreenVisibleReceiver);
        }
        Prefs.getSharedPreferences(mContext)
                .unregisterOnSharedPreferenceChangeListener(mLockScreenPreferenceChangeListener);
    }
}
