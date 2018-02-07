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
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.wearable.complications.ProviderUpdateRequester;
import android.util.Log;

import com.firebase.jobdispatcher.FirebaseJobDispatcher;
import com.firebase.jobdispatcher.GooglePlayDriver;
import com.firebase.jobdispatcher.JobParameters;
import com.firebase.jobdispatcher.Lifetime;
import com.firebase.jobdispatcher.ObservedUri;
import com.firebase.jobdispatcher.SimpleJobService;
import com.firebase.jobdispatcher.Trigger;
import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.BuildConfig;

import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import static com.firebase.jobdispatcher.ObservedUri.Flags.FLAG_NOTIFY_FOR_DESCENDANTS;

/**
 * JobService which listens for artwork change events and updates the Artwork Complication
 */
@TargetApi(Build.VERSION_CODES.N)
public class ArtworkComplicationJobService extends SimpleJobService {
    private static final String TAG = "ArtworkComplJobService";
    private static final int ARTWORK_COMPLICATION_JOB_ID = 65;

    static void scheduleComplicationUpdateJob(Context context) {
        FirebaseJobDispatcher jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
        int result = jobDispatcher.schedule(jobDispatcher.newJobBuilder()
                .setService(ArtworkComplicationJobService.class)
                .setTag("update")
                .setRecurring(true)
                .setLifetime(Lifetime.FOREVER)
                .setTrigger(Trigger.contentUriTrigger(Collections.singletonList(
                        new ObservedUri(MuzeiContract.Artwork.CONTENT_URI, FLAG_NOTIFY_FOR_DESCENDANTS))))
                .build());
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Job scheduled with " + (result == FirebaseJobDispatcher.SCHEDULE_RESULT_SUCCESS ? "success" : "failure"));
        }
    }

    static void cancelComplicationUpdateJob(Context context) {
        FirebaseJobDispatcher jobDispatcher = new FirebaseJobDispatcher(new GooglePlayDriver(context));
        jobDispatcher.cancel("update");
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Job cancelled");
        }
    }

    @Override
    public int onRunJob(final JobParameters job) {
        ProviderUpdateRequester providerUpdateRequester = new ProviderUpdateRequester(this,
                new ComponentName(this, ArtworkComplicationProviderService.class));
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complicationSet = preferences.getStringSet(
                ArtworkComplicationProviderService.KEY_COMPLICATION_IDS, new TreeSet<>());
        if (!complicationSet.isEmpty()) {
            int[] complicationIds = new int[complicationSet.size()];
            int index = 0;
            for (String complicationId : complicationSet) {
                complicationIds[index++] = Integer.parseInt(complicationId);
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Job running, updating " + complicationSet);
            }
            providerUpdateRequester.requestUpdate(complicationIds);
        }
        return RESULT_SUCCESS;
    }
}
