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
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import com.google.android.apps.muzei.FullScreenActivity
import com.google.android.apps.muzei.api.provider.ProviderContract
import com.google.android.apps.muzei.complications.ArtworkComplicationProviderService
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.select
import com.google.android.apps.muzei.wearable.toArtwork
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.experimental.runBlocking
import net.nurik.roman.muzei.BuildConfig
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.reflect.KClass

/**
 * Load artwork from the Wear Data Layer, writing it into [DataLayerArtProvider].
 */
class DataLayerLoadWorker : Worker() {

    companion object {
        private const val TAG = "DataLayerLoadJobService"
        private const val SHOW_ACTIVATE_NOTIFICATION_EXTRA = "SHOW_ACTIVATE_NOTIFICATION"

        /**
         * Load artwork from the Data Layer
         *
         * @param showNotification Show a notification to activate Muzei if the artwork is not found
         */
        fun enqueueLoad(showNotification: Boolean = false) {
            val workManager = WorkManager.getInstance()
            workManager.enqueue(OneTimeWorkRequestBuilder<DataLayerLoadWorker>().apply{
                if (showNotification) {
                    setInputData(Data.Builder()
                            .putBoolean(SHOW_ACTIVATE_NOTIFICATION_EXTRA, true)
                            .build())
                }
            }.build())
        }
    }

    override fun doWork(): WorkerResult = runBlocking {
        loadFromDataLayer()
    }

    private suspend fun loadFromDataLayer(): WorkerResult {
        val showActivateNotification = inputData.getBoolean(SHOW_ACTIVATE_NOTIFICATION_EXTRA,
                false)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Loading artwork from the DataLayer" +
                    if (showActivateNotification) ", activating" else "")
        }
        val dataClient = Wearable.getDataClient(applicationContext)
        try {
            val dataItemBuffer = Tasks.await(dataClient.getDataItems(
                    Uri.parse("wear://*/artwork")))
            if (!dataItemBuffer.status.isSuccess) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "Error getting artwork DataItem")
                }
                if (showActivateNotification) {
                    ActivateMuzeiIntentService.maybeShowActivateMuzeiNotification(applicationContext)
                }
                return WorkerResult.FAILURE
            }
            val dataMap = dataItemBuffer.map {
                DataMapItem.fromDataItem(it).dataMap
            }.firstOrNull { it.containsKey("artwork") && it.containsKey("image") }
            dataItemBuffer.release()
            if (dataMap == null) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "No artwork datamap found.")
                }
                if (showActivateNotification) {
                    ActivateMuzeiIntentService.maybeShowActivateMuzeiNotification(applicationContext)
                }
                return WorkerResult.FAILURE
            }
            val selectedProvider = MuzeiDatabase.getInstance(applicationContext).providerDao()
                    .getCurrentProvider()
            val dataLayerProvider = ComponentName(applicationContext, DataLayerArtProvider::class.java)
            if (selectedProvider == null || dataLayerProvider != selectedProvider.componentName) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Switching to DataLayerProvider")
                }
                DataLayerArtProvider::class.select(applicationContext)
            }
            val result = Tasks.await<DataClient.GetFdForAssetResponse>(
                    dataClient.getFdForAsset(dataMap.getAsset("image")))
            try {
                result.inputStream.use { input ->
                    FileOutputStream(DataLayerArtProvider.getAssetFile(applicationContext)).use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Unable to save asset to local storage", e)
                return WorkerResult.FAILURE
            } finally {
                result.release()
            }
            val artwork = dataMap.getDataMap("artwork").toArtwork()
            val artworkUri = ProviderContract.Artwork.setArtwork(applicationContext,
                    DataLayerArtProvider::class.java, artwork)
            if (artworkUri != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Successfully wrote artwork to $artworkUri")
                }
                enableComponents(
                        FullScreenActivity::class,
                        ArtworkComplicationProviderService::class)
                ActivateMuzeiIntentService.clearNotifications(applicationContext)
            }
            return WorkerResult.SUCCESS
        } catch (e: ExecutionException) {
            Log.w(TAG, "Error getting artwork from Wear Data Layer", e)
            return WorkerResult.FAILURE
        } catch (e: InterruptedException) {
            Log.w(TAG, "Error getting artwork from Wear Data Layer", e)
            return WorkerResult.FAILURE
        }
    }

    private fun enableComponents(vararg components: KClass<*>) {
        components
                .map { ComponentName(applicationContext, it.java) }
                .forEach {
                    applicationContext.packageManager.setComponentEnabledSetting(it,
                            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                            PackageManager.DONT_KILL_APP)
                }
    }
}
