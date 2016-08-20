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
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.wearable.complications.ProviderUpdateRequester;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.util.Set;
import java.util.TreeSet;

/**
 * JobService which listens for artwork change events and updates the Artwork Complication
 */
@TargetApi(Build.VERSION_CODES.N)
public class ArtworkComplicationJobService extends JobService {
    private static final int ARTWORK_COMPLICATION_JOB_ID = 65;

    static void scheduleComplicationUpdateJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        ComponentName componentName = new ComponentName(context, ArtworkComplicationJobService.class);
        jobScheduler.schedule(new JobInfo.Builder(ARTWORK_COMPLICATION_JOB_ID, componentName)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        MuzeiContract.Artwork.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .build());
    }

    static void cancelComplicationUpdateJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(ARTWORK_COMPLICATION_JOB_ID);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        ProviderUpdateRequester providerUpdateRequester = new ProviderUpdateRequester(this,
                new ComponentName(this, ArtworkComplicationProviderService.class));
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        Set<String> complicationSet = preferences.getStringSet(
                ArtworkComplicationProviderService.KEY_COMPLICATION_IDS, new TreeSet<String>());
        if (!complicationSet.isEmpty()) {
            int[] complicationIds = new int[complicationSet.size()];
            int index = 0;
            for (String complicationId : complicationSet) {
                complicationIds[index++] = Integer.parseInt(complicationId);
            }
            providerUpdateRequester.requestUpdate(complicationIds);
        }
        // Schedule the job again to catch the next update to the artwork
        scheduleComplicationUpdateJob(this);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
