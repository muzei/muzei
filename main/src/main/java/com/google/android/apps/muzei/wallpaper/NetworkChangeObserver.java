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
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.os.Build;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.content.WakefulBroadcastReceiver;
import android.util.Log;

import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.sync.TaskQueueService;

import java.util.List;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE;

/**
 * LifecycleObserver responsible for monitoring network connectivity and retrying artwork as necessary
 */
public class NetworkChangeObserver implements DefaultLifecycleObserver {
    private static final String TAG = "NetworkChangeObserver";

    private final Context mContext;
    private BroadcastReceiver mNetworkChangeReceiver = new WakefulBroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            boolean hasConnectivity = !intent.getBooleanExtra(
                    ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
            if (!hasConnectivity) {
                return;
            }
            // Check with components that may not currently be alive but interested in
            // network connectivity changes.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(context);
                if (retryIntent != null) {
                    startWakefulService(context, retryIntent);
                }
            }

            final PendingResult pendingResult = goAsync();
            final LiveData<List<Source>> sourcesLiveData = MuzeiDatabase.getInstance(context).sourceDao()
                    .getCurrentSourcesThatWantNetwork();
            sourcesLiveData.observeForever(new Observer<List<Source>>() {
                @Override
                public void onChanged(@Nullable final List<Source> sources) {
                    sourcesLiveData.removeObserver(this);
                    if (sources != null) {
                        for (Source source : sources) {
                            ComponentName sourceName = source.componentName;
                            try {
                                context.getPackageManager().getServiceInfo(sourceName, 0);
                                context.startService(new Intent(ACTION_NETWORK_AVAILABLE)
                                        .setComponent(sourceName));
                            } catch (PackageManager.NameNotFoundException|IllegalStateException|SecurityException e) {
                                Log.i(TAG, "Sending network available to " + sourceName
                                        + " failed.", e);
                            }
                        }
                    }
                    pendingResult.finish();
                }
            });
        }
    };

    public NetworkChangeObserver(Context context) {
        mContext = context;
    }

    @Override
    public void onCreate(@NonNull final LifecycleOwner owner) {
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
