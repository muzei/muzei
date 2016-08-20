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

import android.annotation.TargetApi;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.wearable.complications.ComplicationData;
import android.support.wearable.complications.ComplicationManager;
import android.support.wearable.complications.ComplicationProviderService;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.util.Set;
import java.util.TreeSet;

/**
 * Provide Muzei backgrounds to other watch faces
 */
@TargetApi(Build.VERSION_CODES.N)
public class ArtworkComplicationProviderService extends ComplicationProviderService {
    static String KEY_COMPLICATION_IDS = "complication_ids";

    @Override
    public void onComplicationActivated(int complicationId, int type, ComplicationManager manager) {
        addComplication(complicationId);
    }

    private void addComplication(int complicationId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complications = preferences.getStringSet(KEY_COMPLICATION_IDS, new TreeSet<String>());
        complications.add(Integer.toString(complicationId));
        preferences.edit().putStringSet(KEY_COMPLICATION_IDS, complications).apply();
        if (complications.size() == 1) {
            ArtworkComplicationJobService.scheduleComplicationUpdateJob(this);
        }
    }

    @Override
    public void onComplicationDeactivated(int complicationId) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complications = preferences.getStringSet(KEY_COMPLICATION_IDS, new TreeSet<String>());
        complications.remove(Integer.toString(complicationId));
        preferences.edit().putStringSet(KEY_COMPLICATION_IDS, complications).apply();
        if (complications.isEmpty()) {
            ArtworkComplicationJobService.cancelComplicationUpdateJob(this);
        }
    }

    @Override
    public void onComplicationUpdate(int complicationId, int type, ComplicationManager complicationManager) {
        // Make sure that the complicationId is really in our set of added complications
        // This fixes corner cases like Muzei being uninstalled and reinstalled
        // (which wipes out our SharedPreferences but keeps any complications activated)
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complications = preferences.getStringSet(KEY_COMPLICATION_IDS, new TreeSet<String>());
        if (!complications.contains(Integer.toString(complicationId))) {
            addComplication(complicationId);
        }
        ComplicationData.Builder builder = new ComplicationData.Builder(type)
                .setLargeImage(Icon.createWithContentUri(MuzeiContract.Artwork.CONTENT_URI));
        complicationManager.updateComplicationData(complicationId, builder.build());
    }
}
