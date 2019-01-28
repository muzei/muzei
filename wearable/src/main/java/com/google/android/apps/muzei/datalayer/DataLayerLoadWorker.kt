/*
 * Copyright 2018 Google Inc.
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

package com.google.android.apps.muzei.datalayer

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.wearable.toArtwork
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.BuildConfig.DATA_LAYER_AUTHORITY
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException

/**
 * Load artwork from the Wear Data Layer, writing it into [DataLayerArtProvider].
 */
class DataLayerLoadWorker(
        context: Context,
        workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "DataLayerLoadJobService"

        /**
         * Load artwork from the Data Layer
         */
        fun enqueueLoad() {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<DataLayerLoadWorker>().build())
        }
    }

    override suspend fun doWork(): Result {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Loading artwork from the DataLayer")
        }
        val dataClient = Wearable.getDataClient(applicationContext)
        try {
            val dataItemBuffer = dataClient.getDataItems(
                    Uri.parse("wear://*/artwork")).await()
            if (!dataItemBuffer.status.isSuccess) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "Error getting artwork DataItem")
                }
                return Result.failure()
            }
            val dataMap = dataItemBuffer.map {
                DataMapItem.fromDataItem(it).dataMap
            }.firstOrNull { it.containsKey("artwork") && it.containsKey("image") }
            dataItemBuffer.release()
            if (dataMap == null) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "No artwork datamap found.")
                }
                return Result.failure()
            }
            val result = dataClient.getFdForAsset(
                    dataMap.getAsset("image")).await()
            try {
                result.inputStream.use { input ->
                    FileOutputStream(DataLayerArtProvider.getAssetFile(applicationContext)).use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Unable to save asset to local storage", e)
                return Result.failure()
            } finally {
                result.release()
            }
            // While CapabilityListenerService should be enabling the
            // DataLayerArtProvider on install, there's no strict ordering
            // between the two so we enable it here as well
            applicationContext.packageManager.setComponentEnabledSetting(
                    ComponentName(applicationContext, DataLayerArtProvider::class.java),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP)
            val artwork = dataMap.getDataMap("artwork").toArtwork()
            val artworkUri = ProviderContract.getProviderClient(applicationContext,
                    DATA_LAYER_AUTHORITY).setArtwork(artwork)
            if (artworkUri != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Successfully wrote artwork to $artworkUri")
                }
            }
            return Result.success()
        } catch (e: ExecutionException) {
            Log.w(TAG, "Error getting artwork from Wear Data Layer", e)
            return Result.failure()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Error getting artwork from Wear Data Layer", e)
            return Result.failure()
        }
    }
}
