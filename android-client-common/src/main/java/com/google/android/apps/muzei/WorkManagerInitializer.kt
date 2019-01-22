package com.google.android.apps.muzei

import android.app.job.JobScheduler
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.preference.PreferenceManager
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import net.nurik.roman.muzei.androidclientcommon.BuildConfig

class WorkManagerInitializer : ContentProvider() {

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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
                val resetVersionCode = sharedPreferences.getLong(KEY_RESET_VERSION_CODE, 0L)
                if (resetVersionCode < RESET_REQUIRED_VERSION_CODE) {
                    val jobScheduler = context.getSystemService(
                            Context.JOB_SCHEDULER_SERVICE) as JobScheduler?
                    jobScheduler?.cancelAll()
                    sharedPreferences.edit {
                        putLong(KEY_RESET_VERSION_CODE, context.packageManager.getPackageInfo(context
                                .packageName, 0)?.run {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                longVersionCode
                            } else {
                                @Suppress("DEPRECATION")
                                versionCode.toLong()
                            }
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
            }

            try {
                WorkManager.initialize(context, Configuration.Builder().build())
            } catch(e: IllegalStateException) {
                // Ignore errors arising from multiple initializations
            }
        }
    }

    override fun onCreate(): Boolean {
        val context = context ?: return true
        safeInitialize(context)
        return true
    }

    override fun query(uri: Uri,
            projection: Array<String>?,
            selection: String?,
            selectionArgs: Array<String>?,
            sortOrder: String?): Cursor? {
        return null
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun delete(uri: Uri,
            selection: String?,
            selectionArgs: Array<String>?): Int {
        return 0
    }

    override fun update(uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<String>?): Int {
        return 0
    }
}
