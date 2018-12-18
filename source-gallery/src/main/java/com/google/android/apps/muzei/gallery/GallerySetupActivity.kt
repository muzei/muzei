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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.observe
import com.google.android.apps.muzei.api.provider.MuzeiArtProvider

class GallerySetupActivity : FragmentActivity() {

    companion object {
        private const val REQUEST_READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE = 1
        private const val REQUEST_CHOOSE_IMAGES = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GalleryDatabase.getInstance(this).chosenPhotoDao()
                .chosenPhotos.observe(this) { chosenUris ->
            val numChosenUris = chosenUris.size
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED || numChosenUris > 0) {
                // If we have permission or have any previously selected images
                setResult(RESULT_OK)
                finish()
            } else {
                ActivityCompat.requestPermissions(this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        REQUEST_READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_READ_EXTERNAL_STORAGE_PERMISSION_REQUEST_CODE) {
            return
        }

        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            GalleryScanWorker.enqueueRescan()
            setResult(RESULT_OK)
            finish()
        } else {
            // Push the user to the GallerySettingsActivity to see inline rationale or just
            // select individual photos
            startActivityForResult(Intent(this, GallerySettingsActivity::class.java).apply {
                if (intent.getBooleanExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, false)) {
                    putExtra(MuzeiArtProvider.EXTRA_FROM_MUZEI, true)
                }
            }, REQUEST_CHOOSE_IMAGES)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CHOOSE_IMAGES) {
            return
        }
        // Pass on the resultCode from the GallerySettingsActivity onto Muzei
        setResult(resultCode)
        finish()
    }
}
