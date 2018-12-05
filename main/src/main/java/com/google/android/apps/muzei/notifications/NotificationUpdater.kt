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

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.util.observeNonNull
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * LifecycleObserver which updates the notification when the artwork changes
 */
class NotificationUpdater(private val context: Context) : DefaultLifecycleObserver {

    override fun onCreate(owner: LifecycleOwner) {
        // Update notifications whenever the artwork changes
        MuzeiDatabase.getInstance(context).artworkDao().currentArtwork
                .observeNonNull(owner) {
            GlobalScope.launch {
                NewWallpaperNotificationReceiver
                        .maybeShowNewArtworkNotification(context)
            }
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        NewWallpaperNotificationReceiver.cancelNotification(context)
    }
}
