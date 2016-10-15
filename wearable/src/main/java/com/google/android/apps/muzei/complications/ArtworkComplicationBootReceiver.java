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

package com.google.android.apps.muzei.complications;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import java.util.Set;
import java.util.TreeSet;

/**
 * BOOT_COMPLETED Receiver which re-registers our MuzeiProvider triggered job to update the
 * artwork complication
 */
public class ArtworkComplicationBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> complications = preferences.getStringSet(
                ArtworkComplicationProviderService.KEY_COMPLICATION_IDS, new TreeSet<String>());
        if (!complications.isEmpty()) {
            ArtworkComplicationJobService.scheduleComplicationUpdateJob(context);
        }
    }
}
