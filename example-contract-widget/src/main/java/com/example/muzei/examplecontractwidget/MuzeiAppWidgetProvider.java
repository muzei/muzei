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

package com.example.muzei.examplecontractwidget;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Handles widget lifecycle events. Ensures that the @ArtworkUpdateReceiver is only enabled when widgets are in use.
 * This prevents us from receiving artwork update broadcasts when the user does not have a widget added.
 */
public class MuzeiAppWidgetProvider extends AppWidgetProvider {
    @Override
    public void onEnabled(final Context context) {
        // Enable the ArtworkUpdateReceiver as we now have widgets that need to be updated
        PackageManager packageManager = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, ArtworkUpdateReceiver.class);
        packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    public void onUpdate(final Context context, final AppWidgetManager appWidgetManager, final int[] appWidgetIds) {
        // Ensure that new widgets are updated with the current artwork
        context.startService(new Intent(context, ArtworkUpdateService.class));
    }

    @Override
    public void onDisabled(final Context context) {
        // Disable the ArtworkUpdateReceiver as we no longer have widgets that need to be updated
        PackageManager packageManager = context.getPackageManager();
        ComponentName componentName = new ComponentName(context, ArtworkUpdateReceiver.class);
        packageManager.setComponentEnabledSetting(componentName, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
