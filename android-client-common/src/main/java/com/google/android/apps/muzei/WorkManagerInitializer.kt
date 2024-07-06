package com.google.android.apps.muzei

import android.app.job.JobScheduler
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.preference.PreferenceManager
import androidx.startup.Initializer
import androidx.work.Configuration
import androidx.work.WorkManager
import net.nurik.roman.muzei.androidclientcommon.BuildConfig

class WorkManagerInitializer : Initializer<Unit> {

    companion object {
        private const val TAG = "WorkManagerInitializer"
        private const val KEY_RESET_VERSION_CODE = "RESET_REQUIRED_VERSION_CODE"
        private const val RESET_REQUIRED_VERSION_CODE = 300019L

        fun initializeObserver(context: Context) = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                safeInitialize(context)
            }
        }

        /**
         * Safely initialize the [WorkManager] with the default [Configuration].
         */
        internal fun safeInitialize(context: Context) {
            // Clear out all system jobs if coming from an older version than RESET_REQUIRED_VERSION_CODE
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
            val resetVersionCode = sharedPreferences.getLong(KEY_RESET_VERSION_CODE, 0L)
            if (resetVersionCode < RESET_REQUIRED_VERSION_CODE) {
                val jobScheduler = context.getSystemService(
                        Context.JOB_SCHEDULER_SERVICE) as JobScheduler?
                jobScheduler?.cancelAll()
                sharedPreferences.edit {
                    putLong(KEY_RESET_VERSION_CODE, context.packageManager.getPackageInfo(context
                            .packageName, 0)?.run {
                        PackageInfoCompat.getLongVersionCode(this)
                    } ?: 0L)
                }
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Cancelled all JobScheduler jobs due to version code of $resetVersionCode")
                }
            } else {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Reset not needed")
                }
            }

            try {
                WorkManager.initialize(context, Configuration.Builder()
                        .apply {
                            if (BuildConfig.DEBUG) {
                                setMinimumLoggingLevel(Log.DEBUG)
                            }
                        }
                        .build())
            } catch(e: IllegalStateException) {
                // Ignore errors arising from multiple initializations
            }
        }
    }

    override fun create(context: Context) = safeInitialize(context)

    override fun dependencies() = mutableListOf<Class<out Initializer<*>>>()
}