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

package com.google.android.apps.muzei.widget

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.observe
import com.google.android.apps.muzei.room.MuzeiDatabase
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * LifecycleObserver which updates the widget when the artwork changes
 */
class WidgetUpdater(private val context: Context) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        // Set up a ContentObserver to update widgets whenever the artwork changes
        val database = MuzeiDatabase.getInstance(context)
        database.artworkDao().currentArtwork.observe(owner) {
            updateAppWidget()
        }
        database.providerDao().currentProvider.observe(owner) {
            updateAppWidget()
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        // Update the widget one last time to disable the 'Next' button until Muzei is reactivated
        updateAppWidget()
    }

    private fun updateAppWidget() {
        GlobalScope.launch {
            updateAppWidget(context.applicationContext)
        }
    }
}
