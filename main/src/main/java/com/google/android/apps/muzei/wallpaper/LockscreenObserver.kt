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

package com.google.android.apps.muzei.wallpaper

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.os.UserManagerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.MuzeiWallpaperService

/**
 * LifecycleObserver responsible for monitoring the state of the lock screen
 */
class LockscreenObserver(
        private val context: Context,
        private val engine: MuzeiWallpaperService.MuzeiWallpaperEngine
) : DefaultLifecycleObserver {

    private val lockScreenVisibleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> engine.lockScreenVisibleChanged(false)
                Intent.ACTION_SCREEN_OFF -> engine.lockScreenVisibleChanged(true)
                Intent.ACTION_SCREEN_ON -> {
                    val kgm = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (!kgm.isKeyguardLocked) {
                        engine.lockScreenVisibleChanged(false)
                    }
                }
            }
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        val intentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        context.registerReceiver(lockScreenVisibleReceiver, intentFilter)
        // If the user is not yet unlocked (i.e., using Direct Boot), we should
        // immediately send the lock screen visible callback
        if (!UserManagerCompat.isUserUnlocked(context)) {
            engine.lockScreenVisibleChanged(true)
        }
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.unregisterReceiver(lockScreenVisibleReceiver)
    }
}
