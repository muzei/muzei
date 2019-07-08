package com.google.android.apps.muzei.legacy

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.apps.muzei.api.MuzeiArtSource

class LegacySourcePackageListener(
        private val applicationContext: Context
) {
    companion object {
        private const val TAG = "LegacySourcePackage"
    }

    private var registered = false

    private val sourcePackageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.data == null) {
                return
            }
            val packageName = intent.data?.schemeSpecificPart
            // Update the sources from the changed package
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Package $packageName changed")
            }
            queryLegacySources()
        }
    }

    fun startListening() {
        if (registered) {
            return
        }
        // Register for package change events
        val packageChangeFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
        }
        applicationContext.registerReceiver(sourcePackageChangeReceiver, packageChangeFilter)
        registered = true
        queryLegacySources()
    }

    private fun queryLegacySources() {
        val queryIntent = Intent(MuzeiArtSource.ACTION_MUZEI_ART_SOURCE)
        val pm = applicationContext.packageManager
        val resolveInfos = pm.queryIntentServices(queryIntent,
                PackageManager.GET_META_DATA)
        for (ri in resolveInfos) {
            if (ri.serviceInfo?.metaData?.containsKey("replacement") == true) {
                // Skip MuzeiArtSources that have a replacement
                continue
            }
            val legacySource = ComponentName(ri.serviceInfo.packageName,
                    ri.serviceInfo.name)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Found legacy source $legacySource")
            }
        }
    }

    fun stopListening() {
        if (!registered) {
            return
        }
        registered = false
        applicationContext.unregisterReceiver(sourcePackageChangeReceiver)
    }
}