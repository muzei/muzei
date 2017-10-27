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

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.Observer;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.featuredart.FeaturedArtSource;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;

/**
 * Broadcast receiver used to watch for changes to installed packages on the device. This triggers
 * a cleanup of sources (in case one was uninstalled), or a data update request to a source
 * if it was updated (its package was replaced).
 */
public class SourcePackageChangeReceiver extends WakefulBroadcastReceiver implements LifecycleOwner {
    private static final String TAG = "SourcePackageChangeRcvr";

    private LifecycleRegistry mLifecycle;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }
        mLifecycle = new LifecycleRegistry(this);
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);

        final String packageName = intent.getData().getSchemeSpecificPart();
        final PendingResult pendingResult = goAsync();
        MuzeiDatabase.getInstance(context).sourceDao().getCurrentSource().observe(this,
                new Observer<Source>() {
                    @Override
                    public void onChanged(@Nullable final Source source) {
                        if (source != null && TextUtils.equals(packageName, source.componentName.getPackageName())) {
                            try {
                                context.getPackageManager().getServiceInfo(source.componentName, 0);
                            } catch (PackageManager.NameNotFoundException e) {
                                Log.i(TAG, "Selected source " + source.componentName
                                        + " is no longer available; switching to default.");
                                SourceManager.selectSource(context,
                                        new ComponentName(context, FeaturedArtSource.class));
                                return;
                            }

                            // Some other change.
                            Log.i(TAG, "Source package changed or replaced. Re-subscribing to " + source.componentName);
                            SourceManager.subscribe(context, source);
                        }
                        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
                        pendingResult.finish();
                    }
                });
    }
}
