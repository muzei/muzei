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

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;

import com.google.android.apps.muzei.event.LockScreenVisibleChangedEvent;
import com.google.android.apps.muzei.settings.Prefs;

import org.greenrobot.eventbus.EventBus;

public class LockScreenVisibleReceiver extends BroadcastReceiver {
    private boolean mRegistered = false;
    private Context mRegisterDeregisterContext;

    private SharedPreferences.OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener
            = new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if (Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED.equals(key)) {
                registerDeregister(sp.getBoolean(Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED, false));
            }
        }
    };

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                EventBus.getDefault().post(new LockScreenVisibleChangedEvent(false));
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                EventBus.getDefault().post(new LockScreenVisibleChangedEvent(true));
            } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                KeyguardManager kgm = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                if (!kgm.inKeyguardRestrictedInputMode()) {
                    EventBus.getDefault().post(new LockScreenVisibleChangedEvent(false));
                }
            }
        }
    }

    private static IntentFilter createIntentFilter() {
        IntentFilter presentFilter = new IntentFilter();
        presentFilter.addAction(Intent.ACTION_USER_PRESENT);
        presentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        presentFilter.addAction(Intent.ACTION_SCREEN_ON);
        return presentFilter;
    }

    public void setupRegisterDeregister(Context context) {
        mRegisterDeregisterContext = context;
        Prefs.getSharedPreferences(context)
                .registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        mOnSharedPreferenceChangeListener.onSharedPreferenceChanged(
                Prefs.getSharedPreferences(context), Prefs.PREF_DISABLE_BLUR_WHEN_LOCKED);
    }

    private void registerDeregister(boolean register) {
        if (mRegistered == register || mRegisterDeregisterContext == null) {
            return;
        }

        if (register) {
            mRegisterDeregisterContext.registerReceiver(this, createIntentFilter());
        } else {
            mRegisterDeregisterContext.unregisterReceiver(this);
        }

        mRegistered = register;
    }

    public void destroy() {
        registerDeregister(false);
        if (mRegisterDeregisterContext != null) {
            Prefs.getSharedPreferences(mRegisterDeregisterContext)
                    .unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
        }
    }
}
