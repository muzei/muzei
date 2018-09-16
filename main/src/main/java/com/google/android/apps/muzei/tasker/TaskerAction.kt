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

package com.google.android.apps.muzei.tasker

import android.os.Bundle
import androidx.core.os.bundleOf

private const val ACTION_NEXT_ARTWORK = "next_artwork"
private const val ACTION_SELECT_PROVIDER = "select_provider"
private const val EXTRA_ACTION = "com.google.android.apps.muzei.TASKER_ACTION"
private const val EXTRA_PROVIDER_AUTHORITY = "com.google.android.apps.muzei.PROVIDER_NAME"

internal sealed class TaskerAction {

    companion object {
        fun fromBundle(bundle: Bundle?) = when(bundle?.getString(EXTRA_ACTION)) {
            ACTION_SELECT_PROVIDER -> bundle.getString(EXTRA_PROVIDER_AUTHORITY)?.let { authority ->
                SelectProviderAction(authority)
            } ?: InvalidAction
            else -> NextArtworkAction
        }
    }

    open fun toBundle(): Bundle = Bundle()
}

internal object NextArtworkAction : TaskerAction() {
    override fun toBundle() = bundleOf(
            EXTRA_ACTION to ACTION_NEXT_ARTWORK)
}

internal class SelectProviderAction(val authority: String) : TaskerAction() {
    override fun toBundle() = bundleOf(
            EXTRA_ACTION to ACTION_SELECT_PROVIDER,
            EXTRA_PROVIDER_AUTHORITY to authority)
}

internal object InvalidAction : TaskerAction()
