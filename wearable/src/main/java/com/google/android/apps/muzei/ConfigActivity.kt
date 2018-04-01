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

package com.google.android.apps.muzei

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.support.wearable.complications.ComplicationData
import android.support.wearable.complications.ComplicationHelperActivity
import net.nurik.roman.muzei.R

/**
 * Configuration Activity for [MuzeiWatchFace]
 */
class ConfigActivity : Activity() {

    companion object {
        private const val CHOOSE_COMPLICATION_REQUEST_CODE = 1
    }

    public override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)
        setContentView(R.layout.config_activity)
        // Immediately start the ComplicationHelperActivity for the bottom complication
        val intent = ComplicationHelperActivity.createProviderChooserHelperIntent(this,
                ComponentName(this, MuzeiWatchFace::class.java),
                MuzeiWatchFace.BOTTOM_COMPLICATION_ID,
                ComplicationData.TYPE_LONG_TEXT,
                ComplicationData.TYPE_RANGED_VALUE,
                ComplicationData.TYPE_SHORT_TEXT)
        startActivityForResult(intent, CHOOSE_COMPLICATION_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        finish()
    }
}
