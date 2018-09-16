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
import com.google.android.apps.muzei.sources.SourceManager
import com.google.android.apps.muzei.sync.ProviderManager
import com.twofortyfouram.locale.api.Intent.ACTION_FIRE_SETTING
import com.twofortyfouram.locale.api.Intent.EXTRA_BUNDLE
import kotlinx.coroutines.experimental.launch

/**
 * Tasker FIRE_SETTING receiver that fires a [TaskerAction]
 */
class TaskerActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE_SETTING) {
            return
        }
        val selectedAction = TaskerAction.fromBundle(intent.getBundleExtra(EXTRA_BUNDLE))
        when (selectedAction) {
            is SelectProviderAction -> {
                val authority = selectedAction.authority
                if (context.packageManager.resolveContentProvider(authority, 0) != null) {
                    val result = goAsync()
                    launch {
                        ProviderManager.select(context, authority)
                        result.finish()
                    }
                }
            }
            is NextArtworkAction -> SourceManager.nextArtwork(context)
        }
    }
}
