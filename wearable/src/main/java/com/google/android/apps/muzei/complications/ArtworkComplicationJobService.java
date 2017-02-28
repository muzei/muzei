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
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.wearable.complications.ProviderUpdateRequester;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.BuildConfig;

import java.util.Set;
import java.util.TreeSet;

/**
 * JobService which listens for artwork change events and updates the Artwork Complication
 */
@TargetApi(Build.VERSION_CODES.N)
public class ArtworkComplicationJobService extends JobService {
    private static final String TAG = "ArtworkComplJobService";
    private static final int ARTWORK_COMPLICATION_JOB_ID = 65;

    static void scheduleComplicationUpdateJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        ComponentName componentName = new ComponentName(context, ArtworkComplicationJobService.class);
        int result = jobScheduler.schedule(new JobInfo.Builder(ARTWORK_COMPLICATION_JOB_ID, componentName)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        MuzeiContract.Artwork.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                .build());
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Job scheduled with " + (result == JobScheduler.RESULT_SUCCESS ? "success" : "failure"));
        }
        // Enable the BOOT_COMPLETED receiver to reschedule the job on reboot
        ComponentName bootReceivedComponentName = new ComponentName(context,
                ArtworkComplicationBootReceiver.class);
        context.getPackageManager().setComponentEnabledSetting(bootReceivedComponentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    static void cancelComplicationUpdateJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        jobScheduler.cancel(ARTWORK_COMPLICATION_JOB_ID);
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Job cancelled");
        }
        // Disable the BOOT_COMPLETED receiver to reduce memory pressure on boot
        ComponentName bootReceivedComponentName = new ComponentName(context,
                ArtworkComplicationBootReceiver.class);
        context.getPackageManager().setComponentEnabledSetting(bootReceivedComponentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
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
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Job running, updating " + complicationSet);
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
