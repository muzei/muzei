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

import android.arch.lifecycle.DefaultLifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.content.Context;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.event.WallpaperActiveStateChangedEvent;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;

import org.greenrobot.eventbus.EventBus;

/**
 * LifecycleObserver responsible for sending analytics callbacks based on the state of the wallpaper
 */
public class WallpaperAnalytics implements DefaultLifecycleObserver {
    private final Context mContext;

    public WallpaperAnalytics(Context context) {
        mContext = context;
    }

    @Override
    public void onCreate(@NonNull final LifecycleOwner owner) {
        FirebaseAnalytics.getInstance(mContext).setUserProperty("device_type", BuildConfig.DEVICE_TYPE);
    }

    @Override
    public void onResume(@NonNull final LifecycleOwner owner) {
        FirebaseAnalytics.getInstance(mContext).logEvent("wallpaper_created", null);
        EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(true));
    }

    @Override
    public void onPause(@NonNull final LifecycleOwner owner) {
        FirebaseAnalytics.getInstance(mContext).logEvent("wallpaper_destroyed", null);
        EventBus.getDefault().postSticky(new WallpaperActiveStateChangedEvent(false));
    }
}
