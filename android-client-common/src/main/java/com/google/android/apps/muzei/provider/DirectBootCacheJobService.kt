package com.google.android.apps.muzei.provider

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.support.annotation.RequiresApi
import android.support.v4.content.ContextCompat
import android.util.Log
import androidx.core.content.systemService
import com.google.android.apps.muzei.api.MuzeiContract
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.launch
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Update the latest artwork in the Direct Boot cache directory whenever the artwork changes
 */
@RequiresApi(Build.VERSION_CODES.N)
class DirectBootCacheJobService : JobService() {

    companion object {
        private const val TAG = "DirectBootCacheJS"
        private const val DIRECT_BOOT_CACHE_JOB_ID = 68
        private val DIRECT_BOOT_CACHE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(15)
        private const val DIRECT_BOOT_CACHE_FILENAME = "current"

        fun scheduleDirectBootCacheJob(context: Context) {
            val jobScheduler = context.systemService<JobScheduler>()
            val componentName = ComponentName(context, DirectBootCacheJobService::class.java)
            jobScheduler.schedule(JobInfo.Builder(DIRECT_BOOT_CACHE_JOB_ID, componentName)
                    .addTriggerContentUri(JobInfo.TriggerContentUri(
                            MuzeiContract.Artwork.CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    // Wait to avoid unnecessarily copying artwork when the user is
                    // quickly switching artwork
                    .setTriggerContentUpdateDelay(DIRECT_BOOT_CACHE_DELAY_MILLIS)
                    .build())
        }

        fun getCachedArtwork(context: Context): File? =
                ContextCompat.createDeviceProtectedStorageContext(context)?.run {
                    File(cacheDir, DIRECT_BOOT_CACHE_FILENAME)
                }
    }

    private lateinit var cacheJob: Job

    override fun onStartJob(params: JobParameters): Boolean {
        @SuppressLint("StaticFieldLeak")
        cacheJob = launch {
            val success = run {
                val artwork = getCachedArtwork(this@DirectBootCacheJobService)
                        ?: return@run false
                try {
                    contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI)?.use { input ->
                        FileOutputStream(artwork).use { out ->
                            input.copyTo(out)
                            if (BuildConfig.DEBUG) {
                                Log.d(TAG, "Successfully wrote artwork to Direct Boot cache")
                            }
                            return@run true
                        }
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to write artwork to direct boot storage", e)
                }
                Log.w(TAG, "Could not open the current artwork")
                return@run false
            }
            jobFinished(params, !success)
        }
        // Schedule the job again to catch the next update to the artwork
        scheduleDirectBootCacheJob(this)
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        cacheJob.cancel()
        return true
    }
}
