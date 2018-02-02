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
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.support.annotation.NonNull;

import com.google.android.apps.muzei.NetworkChangeReceiver;
import com.google.android.apps.muzei.sync.TaskQueueService;

/**
 * LifecycleObserver responsible for monitoring network connectivity and retrying artwork as necessary
 */
public class NetworkChangeObserver implements DefaultLifecycleObserver {
    private final Context mContext;
    private NetworkChangeReceiver mNetworkChangeReceiver;

    public NetworkChangeObserver(Context context) {
        mContext = context;
    }

    @Override
    public void onCreate(@NonNull final LifecycleOwner owner) {
        mNetworkChangeReceiver = new NetworkChangeReceiver();
        IntentFilter networkChangeFilter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        mContext.registerReceiver(mNetworkChangeReceiver, networkChangeFilter);

        // Ensure we retry loading the artwork if the network changed while the wallpaper was disabled
        ConnectivityManager connectivityManager = (ConnectivityManager) mContext.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(mContext);
        if (retryIntent != null && connectivityManager.getActiveNetworkInfo() != null &&
                connectivityManager.getActiveNetworkInfo().isConnected()) {
            mContext.startService(retryIntent);
        }
    }

    @Override
    public void onDestroy(@NonNull final LifecycleOwner owner) {
        mContext.unregisterReceiver(mNetworkChangeReceiver);
    }
}
