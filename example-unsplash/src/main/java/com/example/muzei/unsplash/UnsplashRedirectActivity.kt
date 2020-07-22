/*
 * Copyright 2020 Google Inc.
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

package com.example.muzei.unsplash

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import com.google.android.apps.muzei.api.MuzeiContract

/**
 * This activity's sole purpose is to redirect users to Muzei, which is where they should
 * activate Muzei and then select the Unsplash source.
 *
 * You'll note the usage of the `enable_launcher` boolean resource value to only enable
 * this on API 29+ devices as it is on API 29+ that a launcher icon becomes mandatory for
 * every app.
 */
class UnsplashRedirectActivity : ComponentActivity() {
    companion object {
        private const val TAG = "UnsplashRedirect"
        private const val MUZEI_PACKAGE_NAME = "net.nurik.roman.muzei"
        private const val PLAY_STORE_LINK = "https://play.google.com/store/apps/details?id=$MUZEI_PACKAGE_NAME"
    }

    private val requestLauncher = registerForActivityResult(StartActivityForResult()) {
        // It doesn't matter what the result is, the important part is that the
        // user hit the back button to return to this activity. Since this activity
        // has no UI of its own, we can simply finish the activity.
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First check whether Unsplash is already selected
        val launchIntent = packageManager.getLaunchIntentForPackage(MUZEI_PACKAGE_NAME)
        if (MuzeiContract.Sources.isProviderSelected(this, BuildConfig.UNSPLASH_AUTHORITY)
                && launchIntent != null) {
            // Already selected so just open Muzei
            requestLauncher.launch(launchIntent)
            return
        }
        // Build the list of Intents plus the Toast message that should be displayed
        // to users when we successfully launch one of the Intents
        val intents = listOf(
                MuzeiContract.Sources.createChooseProviderIntent(BuildConfig.UNSPLASH_AUTHORITY)
                        to R.string.toast_enable_unsplash,
                launchIntent
                        to R.string.toast_enable_unsplash_source,
                Intent(Intent.ACTION_VIEW).setData(Uri.parse(PLAY_STORE_LINK))
                        to R.string.toast_muzei_missing_error)
        // Go through each Intent/message pair, trying each in turn
        val success = intents.fold(false) { success, (intent, toastMessage) ->
            if (success) {
                // If one launch has succeeded, we don't need to
                // try any further Intents
                return@fold success
            }
            if (intent == null) {
                // A null Intent means there's nothing to attempt to launch
                return@fold false
            }
            try {
                requestLauncher.launch(intent)
                // Only if the launch succeeds do we show the Toast and trigger success
                Toast.makeText(this, toastMessage, Toast.LENGTH_LONG).show()
                true
            } catch (e: Exception) {
                Log.v(TAG, "Intent $intent failed", e)
                false
            }
        }
        if (!success) {
            // Only if all Intents failed do we show a 'everything failed' Toast
            Toast.makeText(this, R.string.toast_play_store_missing_error, Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
