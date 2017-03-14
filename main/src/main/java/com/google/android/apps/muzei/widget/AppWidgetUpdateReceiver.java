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

package com.google.android.apps.muzei.widget;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.text.TextUtils;

import com.google.android.apps.muzei.api.MuzeiContract;

/**
 * Receive update broadcasts from Muzei and refreshes the widget.
 */
public class AppWidgetUpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            String action = intent.getAction();
            if (TextUtils.equals(action, MuzeiContract.Artwork.ACTION_ARTWORK_CHANGED) ||
                    TextUtils.equals(action, MuzeiContract.Sources.ACTION_SOURCE_CHANGED)) {
                final PendingResult result = goAsync();
                new AppWidgetUpdateTask(context) {
                    @Override
                    protected void onPostExecute(Boolean success) {
                        result.finish();
                    }
                }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        }
    }
}
