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

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.google.android.apps.muzei.event.GainedNetworkConnectivityEvent;

import de.greenrobot.event.EventBus;

public class NetworkChangeReceiver extends WakefulBroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ConnectivityManager.CONNECTIVITY_ACTION.equals(intent.getAction())) {
            return;
        }

        boolean hasConnectivity = !intent.getBooleanExtra(
                ConnectivityManager.EXTRA_NO_CONNECTIVITY, false);
        if (hasConnectivity) {
            EventBus.getDefault().post(new GainedNetworkConnectivityEvent());

            // Check with components that may not currently be alive but interested in
            // network connectivity changes.
            Intent retryIntent = ArtworkCache.maybeRetryDownloadDueToGainedConnectivity(context);
            if (retryIntent != null) {
                startWakefulService(context, retryIntent);
            }

            // TODO: wakeful broadcast?
            SourceManager sm = SourceManager.getInstance(context);
            sm.maybeDispatchNetworkAvailable();
        }
    }
}
