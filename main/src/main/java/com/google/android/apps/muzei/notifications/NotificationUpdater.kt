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

package com.google.android.apps.muzei.notifications

import android.arch.lifecycle.DefaultLifecycleObserver
import android.arch.lifecycle.LifecycleOwner
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread

import com.google.android.apps.muzei.api.MuzeiContract
import kotlinx.coroutines.experimental.runBlocking

/**
 * LifecycleObserver which updates the notification when the artwork changes
 */
class NotificationUpdater(private val context: Context) : DefaultLifecycleObserver {
    private val notificationHandlerThread: HandlerThread by lazy {
        HandlerThread("MuzeiWallpaperService-Notification").apply {
            start()
        }
    }
    private val notificationContentObserver: ContentObserver by lazy {
        object : ContentObserver(Handler(notificationHandlerThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri) {
                runBlocking {
                    NewWallpaperNotificationReceiver.maybeShowNewArtworkNotification(
                            this@NotificationUpdater.context)
                }
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Set up a thread to update notifications whenever the artwork changes
        context.contentResolver.registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, notificationContentObserver)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.contentResolver.unregisterContentObserver(notificationContentObserver)
        notificationHandlerThread.quitSafely()
        NewWallpaperNotificationReceiver.cancelNotification(context)
    }
}
