package com.google.android.apps.muzei.provider;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.google.android.apps.muzei.api.MuzeiContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * Update the latest artwork in the Direct Boot cache directory whenever the artwork changes
 */
@TargetApi(Build.VERSION_CODES.N)
public class DirectBootCacheJobService extends JobService {
    private static final String TAG = "DirectBootCacheJS";
    private static final int DIRECT_BOOT_CACHE_JOB_ID = 68;
    private static final long DIRECT_BOOT_CACHE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(15);
    private static final String DIRECT_BOOT_CACHE_FILENAME = "current";

    private AsyncTask<Void, Void, Boolean> mCacheTask = null;

    static void scheduleDirectBootCacheJob(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        ComponentName componentName = new ComponentName(context, DirectBootCacheJobService.class);
        jobScheduler.schedule(new JobInfo.Builder(DIRECT_BOOT_CACHE_JOB_ID, componentName)
                .addTriggerContentUri(new JobInfo.TriggerContentUri(
                        MuzeiContract.Artwork.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                // Wait to avoid unnecessarily copying artwork when the user is
                // quickly switching artwork
                .setTriggerContentUpdateDelay(DIRECT_BOOT_CACHE_DELAY_MILLIS)
                .build());
    }

    static File getCachedArtwork(Context context) {
        Context directBootContext = ContextCompat.createDeviceProtectedStorageContext(context);
        return directBootContext == null
                ? null
                : new File(directBootContext.getCacheDir(), DIRECT_BOOT_CACHE_FILENAME);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        mCacheTask = new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... voids) {
                File artwork = getCachedArtwork(DirectBootCacheJobService.this);
                if (artwork == null) {
                    return false;
                }
                try (InputStream in = getContentResolver().openInputStream(MuzeiContract.Artwork.CONTENT_URI);
                     FileOutputStream out = new FileOutputStream(artwork)) {
                    if (in == null) {
                        return false;
                    }
                    byte[] buffer = new byte[1024];
                    int read;
                    while((read = in.read(buffer)) != -1){
                        out.write(buffer, 0, read);
                    }
                    return true;
                } catch (IOException e) {
                    Log.e(TAG, "Unable to write artwork to direct boot storage", e);
                }
                return false;
            }

            @Override
            protected void onPostExecute(Boolean success) {
                jobFinished(params, !success);
            }
        };
        mCacheTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        // Schedule the job again to catch the next update to the artwork
        scheduleDirectBootCacheJob(this);
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mCacheTask != null) {
            mCacheTask.cancel(true);
        }
        return true;
    }
}
