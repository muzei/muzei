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

package com.google.android.apps.muzei.provider

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.apps.muzei.api.MuzeiContract
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.androidclientcommon.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DirectBootCache {
    private const val TAG = "DirectBootCache"
    private val DIRECT_BOOT_CACHE_DELAY_MILLIS = TimeUnit.SECONDS.toMillis(15)
    private const val DIRECT_BOOT_CACHE_FILENAME = "current"

    fun getCachedArtwork(context: Context): File? =
            ContextCompat.createDeviceProtectedStorageContext(context)?.run {
                File(cacheDir, DIRECT_BOOT_CACHE_FILENAME)
            }

    private var cacheJob: Job? = null

    internal fun onArtworkChanged(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            // No Direct Boot prior to Android N
            return
        }
        cacheJob?.cancel()
        cacheJob = GlobalScope.launch {
            delay(DIRECT_BOOT_CACHE_DELAY_MILLIS)
            if (cacheJob?.isCancelled == true) {
                return@launch
            }
            val artwork = getCachedArtwork(context)
                    ?: return@launch
            try {
                context.contentResolver.openInputStream(MuzeiContract.Artwork.CONTENT_URI)?.use { input ->
                    FileOutputStream(artwork).use { out ->
                        input.copyTo(out)
                        if (BuildConfig.DEBUG) {
                            Log.d(TAG, "Successfully wrote artwork to Direct Boot cache")
                        }
                        return@launch
                    }
                }
                Log.w(TAG, "Could not open the current artwork")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to write artwork to direct boot storage", e)
            }
        }
    }
}
