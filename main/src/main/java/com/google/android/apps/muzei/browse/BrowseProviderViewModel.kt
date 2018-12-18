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
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.viewModelScope
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ProviderArtworkLiveData(
        val context: Context,
        val coroutineScope: CoroutineScope,
        val contentUri: Uri
): MutableLiveData<List<Artwork>>() {
    private val authority: String = contentUri.authority
            ?: throw IllegalArgumentException("Invalid contentUri $contentUri")
    private val singleThreadContext by lazy {
        Executors.newSingleThreadExecutor { target ->
            Thread(target, "ProviderArtworkLiveData")
        }.asCoroutineDispatcher()
    }
    private val contentObserver = object : ContentObserver(Handler()) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshArt()
        }
    }
    private lateinit var contentProviderClient: ContentProviderClientCompat
    private var open: Boolean = false

    override fun onActive() {
        contentProviderClient = ContentProviderClientCompat.getClient(context, contentUri)
                ?: return
        context.contentResolver.registerContentObserver(
                contentUri,
                true,
                contentObserver)
        open = true
        refreshArt()
    }

    override fun onInactive() {
        if (open) {
            context.contentResolver.unregisterContentObserver(contentObserver)
            contentProviderClient.close()
            open = false
        }
    }

    private fun refreshArt() {
        coroutineScope.launch(singleThreadContext) {
            val list = mutableListOf<Artwork>()
            contentProviderClient.query(contentUri)?.use { data ->
                while(data.moveToNext()) {
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
            postValue(list)
        }
    }
}

class BrowseProviderViewModel(
        application: Application
): AndroidViewModel(application) {
    private val contentUriLiveData = MutableLiveData<Uri>()

    fun setContentUri(contentUri: Uri) {
        contentUriLiveData.value = contentUri
    }

    val artLiveData: LiveData<List<Artwork>> = Transformations
            .switchMap(contentUriLiveData) { contentUri ->
                ProviderArtworkLiveData(application, viewModelScope, contentUri)
            }
}
