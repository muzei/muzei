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

import android.annotation.SuppressLint
import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import com.google.android.apps.muzei.api.MuzeiContract
import java.util.concurrent.Executor
import java.util.concurrent.Executors

private val EXECUTOR: Executor by lazy {
    Executors.newSingleThreadExecutor()
}

@SuppressLint("StaticFieldLeak")
private var currentTask: AppWidgetUpdateTask? = null

internal fun AppWidgetUpdateTask.executeUpdate() {
    currentTask?.cancel(true)
    currentTask = also {
        it.executeOnExecutor(EXECUTOR)
    }
}

/**
 * LifecycleObserver which updates the widget when the artwork changes
 */
class WidgetUpdater(private val context: Context) : DefaultLifecycleObserver {

    private val widgetContentObserver: ContentObserver by lazy {
        object : ContentObserver(Handler()) {
            override fun onChange(selfChange: Boolean, uri: Uri) {
                AppWidgetUpdateTask(context).executeUpdate()
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Set up a ContentObserver to update widgets whenever the artwork changes
        context.contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, widgetContentObserver)
        context.contentResolver.registerContentObserver(MuzeiContract.Sources.CONTENT_URI,
                true, widgetContentObserver)
        // Update the widget now that the wallpaper is active to enable the 'Next' button if supported
        widgetContentObserver.onChange(true)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.contentResolver.unregisterContentObserver(widgetContentObserver)
        // Update the widget one last time to disable the 'Next' button until Muzei is reactivated
        AppWidgetUpdateTask(context).executeUpdate()
    }
}
