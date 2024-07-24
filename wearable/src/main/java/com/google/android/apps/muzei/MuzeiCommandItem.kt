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
import android.app.PendingIntent
import android.graphics.Rect
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.app.RemoteActionCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.get
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.wear.widget.RoundedDrawable
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.util.collectIn
import com.google.android.apps.muzei.util.sendFromBackground
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.databinding.MuzeiCommandItemBinding
import net.nurik.roman.muzei.androidclientcommon.R as CommonR

data class ArtworkCommand(
        private val artwork: Artwork,
        private val command: RemoteActionCompat
) {
    val providerAuthority = artwork.providerAuthority
    val title = command.title
    val actionIntent = command.actionIntent
    val icon = command.icon
}

class MuzeiCommandViewModel(application: Application) : AndroidViewModel(application) {
    val commands = MuzeiDatabase.getInstance(application).artworkDao().getCurrentArtworkFlow()
      .map { artwork ->
        artwork?.getCommands(application)?.sortedByDescending { command ->
            command.shouldShowIcon()
        }?.map { command ->
            ArtworkCommand(artwork, command)
        } ?: emptyList()
    }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)
}

class MuzeiCommandViewHolder(
        private val binding: MuzeiCommandItemBinding
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(artworkCommand: ArtworkCommand) = binding.run {
        val context = root.context
        command.text = artworkCommand.title
        command.setCompoundDrawablesRelative(RoundedDrawable().apply {
            radius = root.context.resources.getDimensionPixelSize(R.dimen.art_detail_open_on_phone_radius)
            backgroundColor = ContextCompat.getColor(context, CommonR.color.theme_primary)
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
            try {
                artworkCommand.actionIntent.sendFromBackground()
            } catch (e: PendingIntent.CanceledException) {
                // Why do you give us a cancelled PendingIntent.
                // We can't do anything with that.
            }
        }
    }
}

class MuzeiCommandAdapter<O>(owner: O) : ListAdapter<ArtworkCommand, MuzeiCommandViewHolder>(
        object : DiffUtil.ItemCallback<ArtworkCommand>() {
            override fun areItemsTheSame(
                    artworkCommand1: ArtworkCommand,
                    artworkCommand2: ArtworkCommand
            ) = artworkCommand1.title == artworkCommand2.title

            override fun areContentsTheSame(
                    artworkCommand1: ArtworkCommand,
                    artworkCommand2: ArtworkCommand
            ) = artworkCommand1 == artworkCommand2
        }
) where O : LifecycleOwner, O : ViewModelStoreOwner {
    init {
        val viewModel: MuzeiCommandViewModel = ViewModelProvider(owner).get()
        viewModel.commands.collectIn(owner) { commands ->
            submitList(commands)
        }
    }

    override fun onCreateViewHolder(
            parent: ViewGroup,
            viewType: Int
    ) = MuzeiCommandViewHolder(MuzeiCommandItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: MuzeiCommandViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}