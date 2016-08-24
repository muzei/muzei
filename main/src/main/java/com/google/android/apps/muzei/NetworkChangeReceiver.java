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
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.os.Build;
import android.support.v4.content.WakefulBroadcastReceiver;

import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.sync.TaskQueueService;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE;

public class NetworkChangeReceiver extends WakefulBroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
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
            Cursor selectedSources = context.getContentResolver().query(MuzeiContract.Sources.CONTENT_URI,
                    new String[]{MuzeiContract.Sources.COLUMN_NAME_COMPONENT_NAME},
                    MuzeiContract.Sources.COLUMN_NAME_IS_SELECTED + "=1 AND " +
                    MuzeiContract.Sources.COLUMN_NAME_WANTS_NETWORK_AVAILABLE + "=1", null, null, null);
            if (selectedSources != null && selectedSources.moveToPosition(-1)) {
                while (selectedSources.moveToNext()) {
                    String componentName = selectedSources.getString(0);
                    context.startService(new Intent(ACTION_NETWORK_AVAILABLE)
                            .setComponent(ComponentName.unflattenFromString(componentName)));
                }
            }
            if (selectedSources != null) {
                selectedSources.close();
            }
        }
    }
}
