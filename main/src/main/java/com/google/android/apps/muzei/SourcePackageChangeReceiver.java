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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.text.TextUtils;
import android.util.Log;

/**
 * Broadcast receiver used to watch for changes to installed packages on the device. This triggers
 * a cleanup of sources (in case one was uninstalled), or a data update request to a source
 * if it was updated (its package was replaced).
 */
public class SourcePackageChangeReceiver extends WakefulBroadcastReceiver {
    private static final String TAG = "SourcePackageChangeRcvr";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return;
        }

        String packageName = intent.getData().getSchemeSpecificPart();
        SourceManager sourceManager = SourceManager.getInstance(context);
        ComponentName selectedComponent = sourceManager.getSelectedSource();
        if (!TextUtils.equals(packageName, selectedComponent.getPackageName())) {
            return;
        }

        try {
            context.getPackageManager().getServiceInfo(selectedComponent, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.i(TAG, "Selected source no longer available; switching to default.");
            sourceManager.selectDefaultSource();
            return;
        }

        // Some other change.
        Log.i(TAG, "Source package changed or replaced. Re-subscribing.");
        sourceManager.subscribeToSelectedSource();
    }
}
