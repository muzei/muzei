/*
 * Copyright 2015 Google Inc.
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

package com.google.android.apps.muzei.gallery

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.launch
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider

private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.ACCESS_MEDIA_LOCATION)
} else {
    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}
internal class RequestStoragePermissions : ActivityResultContract<Unit, Boolean>() {
    private val requestMultiplePermissions = RequestMultiplePermissions()

    override fun createIntent(context: Context, input: Unit?) =
            requestMultiplePermissions.createIntent(context, permissions)

    override fun getSynchronousResult(
            context: Context,
            input: Unit?
    ) = requestMultiplePermissions.getSynchronousResult(context, permissions)?.let { result ->
        SynchronousResult(result.value.getOrElse(Manifest.permission.READ_EXTERNAL_STORAGE) { false })
    }

    override fun parseResult(
            resultCode: Int,
            intent: Intent?
    ): Boolean = requestMultiplePermissions.parseResult(resultCode, intent).let { result ->
        result.getOrElse(Manifest.permission.READ_EXTERNAL_STORAGE) { false }
    }
}

class GallerySetupActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GalleryDatabase.getInstance(this).chosenPhotoDao()
                .chosenPhotosLiveData.observe(this) { chosenUris ->
            val numChosenUris = chosenUris.size
            val hasPermission = permissions.all { permission ->
                ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
            }
            if (hasPermission || numChosenUris > 0) {
                // If we have permission or have any previously selected images
                setResult(RESULT_OK)
                finish()
            } else {
                requestStoragePermission.launch()
            }
        }
    }

    private val requestStoragePermission = registerForActivityResult(
            RequestStoragePermissions()) { granted ->
        if (granted) {
            GalleryScanWorker.enqueueRescan(this)
            setResult(RESULT_OK)
            finish()
        } else {
            // Push the user to the GallerySettingsActivity to see inline rationale or just
            // select individual photos
            startSettings.launch(Intent(this, GallerySettingsActivity::class.java).apply {
                if (intent.getBooleanExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, false)) {
                    putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
                }
            })
        }
    }

    private val startSettings = registerForActivityResult(StartActivityForResult()) { (resultCode, _) ->
        // Pass on the resultCode from the GallerySettingsActivity onto Muzei
        setResult(resultCode)
        finish()
    }
}
