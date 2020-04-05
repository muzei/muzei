/*
 * Copyright 2020 Google Inc.
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

import android.app.Application
import android.graphics.Rect
import androidx.core.app.RemoteActionCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.wear.widget.RoundedDrawable
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.ktx.Firebase
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiCommandItemBinding

data class ArtworkCommand(
        private val artwork: Artwork,
        private val command: RemoteActionCompat
) {
    val providerAuthority = artwork.providerAuthority
    val title = command.title
    val actionIntent = command.actionIntent
    val icon = command.icon
    fun shouldShowIcon() = command.shouldShowIcon()
}

class MuzeiCommandViewModel(application: Application) : AndroidViewModel(application) {
    val commandsLiveData = MuzeiDatabase.getInstance(application).artworkDao().currentArtwork.switchMap { artwork ->
        liveData {
            if (artwork != null) {
                emit(artwork.getCommands(getApplication<Application>()).map { command ->
                    ArtworkCommand(artwork, command)
                })
            } else {
                emit(emptyList<ArtworkCommand>())
            }
        }
    }
}

fun MuzeiCommandItemBinding.bind(artworkCommand: ArtworkCommand) {
    val context = root.context
    command.text = artworkCommand.title
    command.setCompoundDrawablesRelative(RoundedDrawable().apply {
        isClipEnabled = true
        radius = root.context.resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
        backgroundColor = ContextCompat.getColor(context, R.color.theme_primary)
        drawable = artworkCommand.icon.loadDrawable(context)
        bounds = Rect(0, 0, radius * 2, radius * 2)
    }, null, null, null)
    command.setOnClickListener {
        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
            param(FirebaseAnalytics.Param.ITEM_LIST_ID, artworkCommand.providerAuthority)
            param(FirebaseAnalytics.Param.ITEM_NAME, artworkCommand.title.toString())
            param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
            param(FirebaseAnalytics.Param.CONTENT_TYPE, "wear_activity")
        }
        artworkCommand.actionIntent.send()
    }
}
