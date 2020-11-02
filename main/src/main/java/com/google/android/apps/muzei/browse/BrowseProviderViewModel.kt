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

package com.google.android.apps.muzei.browse

import android.app.Application
import android.content.ContentUris
import android.database.ContentObserver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class BrowseProviderViewModel(
        application: Application
): AndroidViewModel(application) {

    private val contentUriSharedFlow = MutableSharedFlow<Uri>(
            replay = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)
    var contentUri: Uri
        get() = contentUriSharedFlow.replayCache.first()
        set(value) {
            contentUriSharedFlow.tryEmit(value)
        }

    private fun getProviderArtwork(contentUri: Uri) = callbackFlow {
        val context = getApplication<Application>()
        val authority: String = contentUri.authority
                ?: throw IllegalArgumentException("Invalid contentUri $contentUri")
        val contentProviderClient = ContentProviderClientCompat.getClient(
                context, contentUri)
        var refreshJob: Job? = null
        val refreshArt = {
            refreshJob?.cancel()
            refreshJob = launch {
                val list = mutableListOf<Artwork>()
                contentProviderClient?.query(contentUri)?.use { data ->
                    while(data.moveToNext() && isActive) {
                        val providerArtwork =
                                com.google.android.apps.muzei.api.provider.Artwork.fromCursor(data)
                        list.add(Artwork(ContentUris.withAppendedId(contentUri,
                                providerArtwork.id)).apply {
                            title = providerArtwork.title
                            byline = providerArtwork.byline
                            attribution = providerArtwork.attribution
                            providerAuthority = authority
                        })
                    }
                }
                send(list)
            }
        }
        val contentObserver = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                refreshArt()
            }
        }
        context.contentResolver.registerContentObserver(
                contentUri,
                true,
                contentObserver)
        refreshArt()

        awaitClose {
            context.contentResolver.unregisterContentObserver(contentObserver)
            contentProviderClient?.close()
        }
    }

    val artwork = contentUriSharedFlow.distinctUntilChanged()
            .flatMapLatest { contentUri ->
                getProviderArtwork(contentUri)
            }.shareIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), 1)
}
