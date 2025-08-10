/*
 * Copyright 2025 Google Inc.
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
import android.app.PendingIntent
import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.google.android.apps.muzei.api.internal.ProtocolConstants.METHOD_MARK_ARTWORK_LOADED
import com.google.android.apps.muzei.room.Artwork
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.room.getCommands
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.ContentProviderClientCompat
import com.google.android.apps.muzei.util.sendFromBackground
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

private const val REFRESH_DELAY = 300L // milliseconds

@Composable
fun BrowseProvider(
    contentUri: android.net.Uri,
    onUp: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel = viewModel {
        BrowseProviderViewModel(
            context.applicationContext as Application,
            createSavedStateHandle()
        )
    }
    val client by viewModel.client.collectAsState(null)
    val label by remember(viewModel.providerInfo) {
        viewModel.providerInfo.map { info ->
            info?.loadLabel(context.packageManager)?.toString()
        }
    }.collectAsState(null)
    val coroutineScope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }
    val artworkList by viewModel.artwork.collectAsState(listOf())
    BrowseProviderScreen(
        label = label,
        onUp = onUp,
        onRefresh = {
            coroutineScope.launch {
                isRefreshing = true
                ProviderManager.requestLoad(context, contentUri)
                // Show the refresh indicator for some visible amount of time
                // rather than immediately dismissing it. We don't know how long
                // the provider will actually take to refresh, if it does at all.
                delay(REFRESH_DELAY)
                isRefreshing = false
            }
        },
        isRefreshing = isRefreshing,
        artworkList = artworkList,
        actionsProvider = { artwork ->
            artwork.getCommands(context).map { remoteAction ->
                remoteAction.title.toString()
            }.filterNot {
                it.isBlank()
            }
        },
        onArtworkClick = { artwork ->
            onArtworkClicked(artwork, context, client)
        },
        onActionClick = { artwork, action ->
            onActionClicked(artwork, context, action)
        },
    )
}

private suspend fun onArtworkClicked(
    artwork: Artwork,
    context: Context,
    client: ContentProviderClientCompat?
) {
    Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
        param(FirebaseAnalytics.Param.ITEM_LIST_ID, artwork.providerAuthority)
        param(FirebaseAnalytics.Param.ITEM_NAME, artwork.title ?: "")
        param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
        param(FirebaseAnalytics.Param.CONTENT_TYPE, "browse")
    }
    // Ensure the date added is set to the current time
    artwork.dateAdded.time = System.currentTimeMillis()
    MuzeiDatabase.getInstance(context).artworkDao()
        .insert(artwork)
    client?.call(METHOD_MARK_ARTWORK_LOADED, artwork.imageUri.toString())
    context.toast(
        if (artwork.title.isNullOrBlank()) {
            context.getString(R.string.browse_set_wallpaper)
        } else {
            context.getString(
                R.string.browse_set_wallpaper_with_title,
                artwork.title
            )
        }
    )
}

private suspend fun onActionClicked(
    artwork: Artwork,
    context: Context,
    action: String
) {
    val actions = artwork.getCommands(context)
    val selectedAction = actions.find { it.title.toString() == action }
    if (selectedAction != null) {
        Firebase.analytics.logEvent(FirebaseAnalytics.Event.SELECT_ITEM) {
            param(FirebaseAnalytics.Param.ITEM_LIST_ID, artwork.providerAuthority)
            param(
                FirebaseAnalytics.Param.ITEM_NAME,
                selectedAction.title.toString()
            )
            param(FirebaseAnalytics.Param.ITEM_LIST_NAME, "actions")
            param(FirebaseAnalytics.Param.CONTENT_TYPE, "browse")
        }
        try {
            selectedAction.actionIntent.sendFromBackground()
        } catch (_: PendingIntent.CanceledException) {
            // Why do you give us a cancelled PendingIntent.
            // We can't do anything with that.
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseProviderScreen(
    label: String? = null,
    onUp: () -> Unit = {},
    onRefresh: () -> Unit = {},
    isRefreshing: Boolean = false,
    artworkList: List<Artwork> = emptyList(),
    actionsProvider: suspend (Artwork) -> List<String> = { emptyList() },
    onArtworkClick: suspend (Artwork) -> Unit = {},
    onActionClick: suspend (Artwork, String) -> Unit = { _, _ -> },
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    label?.let {
                        Text(text = it)
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onUp,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up)
                        )
                    }
                },
                actions = {
                    var expanded by remember { mutableStateOf(false) }
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.menu_overflow)
                        )
                    }
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.browse_refresh)) },
                            onClick = {
                                expanded = false
                                onRefresh()
                            }
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 160.dp)
            ) {
                items(
                    items = artworkList,
                    key = { artwork -> artwork.imageUri }
                ) { artwork ->
                    val actions by produceState(emptyList()) {
                        value = actionsProvider(artwork)
                    }
                    var expanded by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    AsyncImage(
                        model = artwork.imageUri,
                        contentDescription = artwork.title,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onLongClick = {
                                    if (actions.isNotEmpty()) {
                                        expanded = true
                                    }
                                }
                            ) {
                                coroutineScope.launch {
                                    onArtworkClick(artwork)
                                }
                            },
                        contentScale = ContentScale.Crop,
                    )
                    DropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        for (action in actions) {
                            DropdownMenuItem(
                                text = { Text(action) },
                                onClick = {
                                    coroutineScope.launch {
                                        expanded = false
                                        onActionClick(artwork, action)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Preview
@Composable
fun BrowseProviderScreenPreview() {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        AppTheme {
            BrowseProviderScreen(
                label = "Preview Provider",
                artworkList = List(100) { index ->
                    Artwork(imageUri = "$index".toUri()).apply {
                        id = index.toLong()
                        title = "Preview $index"
                    }
                },
                actionsProvider = { artwork ->
                    listOf("Action ${artwork.id}")
                }
            )
        }
    }
}