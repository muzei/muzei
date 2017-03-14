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

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;

import com.google.android.apps.muzei.SourceManager;
import com.google.android.apps.muzei.api.MuzeiArtSource;

/**
 * AppWidgetProvider for Muzei. The actual updating is done asynchronously in
 * {@link AppWidgetUpdateTask}.
 */
public class MuzeiAppWidgetProvider extends AppWidgetProvider {
    static final String ACTION_NEXT_ARTWORK = "com.google.android.apps.muzei.action.WIDGET_NEXT_ARTWORK";

    @Override
    public void onEnabled(final Context context) {
        // Enable the AppWidgetUpdateReceiver as we now have widgets that need to be updated
        PackageManager packageManager = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, AppWidgetUpdateReceiver.class);
        packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (intent != null && ACTION_NEXT_ARTWORK.equals(intent.getAction())) {
            SourceManager.sendAction(context.getApplicationContext(), MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        updateWidgets(context);
    }


    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager appWidgetManager,
                                          int appWidgetId, Bundle newOptions) {
        updateWidgets(context);
    }

    private void updateWidgets(final Context context) {
        final PendingResult result = goAsync();
        new AppWidgetUpdateTask(context) {
            @Override
            protected void onPostExecute(Boolean success) {
                result.finish();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    @Override
    public void onDisabled(final Context context) {
        // Disable the AppWidgetUpdateReceiver as we no longer have widgets that need to be updated
        PackageManager packageManager = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, AppWidgetUpdateReceiver.class);
        packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
