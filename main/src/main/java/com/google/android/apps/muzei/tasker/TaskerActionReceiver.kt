/*
 * Copyright 2014 Google Inc.
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

package com.google.android.apps.muzei.tasker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.apps.muzei.legacy.LegacySourceManager
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.goAsync
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.joaomgcd.taskerpluginlibrary.TaskerPluginConstants.ACTION_FIRE_SETTING
import com.joaomgcd.taskerpluginlibrary.TaskerPluginConstants.EXTRA_BUNDLE

/**
 * Tasker FIRE_SETTING receiver that fires a [TaskerAction]
 */
class TaskerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE_SETTING) {
            return
        }
        goAsync {
            when (val selectedAction = TaskerAction.fromBundle(
                    intent.getBundleExtra(EXTRA_BUNDLE))) {
                is SelectProviderAction -> {
                    val authority = selectedAction.authority
                    @Suppress("DEPRECATION")
                    if (context.packageManager.resolveContentProvider(authority, 0) != null) {
                        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
                            param(FirebaseAnalytics.Param.ITEM_LIST_ID, authority)
                            param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "providers")
                            param(FirebaseAnalytics.Param.CONTENT_TYPE, "tasker")
                        }
                        ProviderManager.select(context, authority)
                    }
                }
                is NextArtworkAction -> {
                    Firebase.analytics.logEvent("next_artwork") {
                        param(FirebaseAnalytics.Param.CONTENT_TYPE, "tasker")
                    }
                    LegacySourceManager.getInstance(context).nextArtwork()
                }
                is InvalidAction -> {}
            }
        }
    }
}