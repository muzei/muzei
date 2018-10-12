/*
 * Copyright 2017 Google Inc.
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

package com.google.android.apps.muzei.single

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Setup Activity which ensures that the user has a single image selected
 */
class SingleSetupActivity : Activity() {

    companion object {
        private const val REQUEST_SELECT_IMAGE = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (SingleArtProvider.getArtworkFile(this).exists()) {
            // We already have a single artwork available
            setResult(RESULT_OK)
            finish()
        } else {
            startActivityForResult(
                    Intent(this, SingleSettingsActivity::class.java),
                    REQUEST_SELECT_IMAGE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // Pass on the resultCode from the SingleSettingsActivity onto Muzei
        setResult(if (requestCode == REQUEST_SELECT_IMAGE) resultCode else RESULT_CANCELED)
        finish()
    }
}
