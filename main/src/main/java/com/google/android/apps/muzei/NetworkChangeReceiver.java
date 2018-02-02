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

public class NetworkChangeReceiver extends WakefulBroadcastReceiver implements LifecycleOwner {
    private static final String TAG = "NetworkChangeReceiver";

    private LifecycleRegistry mLifecycle;

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onReceive(final Context context, Intent intent) {
        mLifecycle = new LifecycleRegistry(this);
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        boolean hasConnectivity = !intent.getBooleanExtra(
                ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
        if (hasConnectivity) {
            // Check with components that may not currently be alive but interested in
            // network connectivity changes.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                Intent retryIntent = TaskQueueService.maybeRetryDownloadDueToGainedConnectivity(context);
                if (retryIntent != null) {
                    startWakefulService(context, retryIntent);
                }
            }

            // TODO: wakeful broadcast?
            final PendingResult pendingResult = goAsync();
            MuzeiDatabase.getInstance(context).sourceDao().getCurrentSourcesThatWantNetwork().observe(this,
                    sources -> {
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
                        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
                        pendingResult.finish();
                    });
        }
    }
}
