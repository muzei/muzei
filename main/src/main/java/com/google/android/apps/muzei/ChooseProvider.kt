/*
 * Copyright 2026 Google Inc.
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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.DrawerDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.ModalRightNavigationDrawer
import com.google.android.apps.muzei.util.plus
import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooseProvider(
    providers: List<ProviderInfo>,
    modifier: Modifier = Modifier,
    drawerSheetContent: @Composable ColumnScope.() -> Unit = {},
    drawerSheetContainerColor: Color = DrawerDefaults.modalContainerColor,
    onNotificationSettingsClick: () -> Unit = {},
    autoScrollToProviderAuthority: String? = null,
    onAutoScrollToProviderCompleted: () -> Unit = {},
    onClick: (ProviderInfo) -> Unit = {},
    onLongClick: (ProviderInfo) -> Unit = {},
    onSettingsClick: (ProviderInfo) -> Unit = {},
    onBrowseClick: (ProviderInfo) -> Unit = {},
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    ModalRightNavigationDrawer(
        drawerSheetContent = drawerSheetContent,
        modifier = modifier,
        drawerState = drawerState,
        drawerSheetContainerColor = drawerSheetContainerColor,
    ) {
        val scrollBehavior =
            TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                TopAppBar(
                    title = {},
                    actions = {
                        val coroutineScope = rememberCoroutineScope()
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    Firebase.analytics.logEvent("auto_advance_open", null)
                                    drawerState.open()
                                }
                            },
                        ) {
                            Icon(
                                Icons.Default.Update,
                                contentDescription = stringResource(R.string.auto_advance_settings),
                            )
                        }
                        // Show the menu items in a DropdownMenu
                        var expanded by remember { mutableStateOf(false) }
                        IconButton(onClick = { expanded = !expanded }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = null
                            )
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.notification_settings)) },
                                onClick = {
                                    expanded = false
                                    onNotificationSettingsClick()
                                }
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                        navigationIconContentColor = Color.White,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White,
                        subtitleContentColor = Color.White,
                    ),
                    scrollBehavior = scrollBehavior
                )
            },
            containerColor = Color.Transparent,
            contentColor = Color.White,
        ) { innerPadding ->
            val state = rememberLazyStaggeredGridState()
            LaunchedEffect(autoScrollToProviderAuthority) {
                val index = providers.indexOfFirst { it.authority == autoScrollToProviderAuthority }
                if (autoScrollToProviderAuthority != null && index != -1) {
                    state.animateScrollToItem(index)
                    onAutoScrollToProviderCompleted()
                }
            }
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Adaptive(minSize = 300.dp),
                state = state,
                contentPadding = innerPadding + PaddingValues(horizontal = 16.dp) +
                        PaddingValues(bottom = 16.dp),
                verticalItemSpacing = 16.dp,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(
                    items = providers,
                    key = { it.authority },
                ) { providerInfo ->
                    ChooseProviderItem(
                        providerInfo = providerInfo,
                        onClick = {
                            onClick(providerInfo)
                        },
                        onLongClick = {
                            onLongClick(providerInfo)
                        },
                        onSettingsClick = {
                            onSettingsClick(providerInfo)
                        },
                        onBrowseClick = {
                            onBrowseClick(providerInfo)
                        }
                    )
                }
            }
        }
    }
}

@Preview(name = "Portrait", device = PHONE)
@Preview(
    name = "Landscape",
    device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420",
)
@Preview(name = "Tablet - Landscape", device = TABLET)
@OptIn(ExperimentalCoilApi::class)
@Composable
fun ChooseProviderPreview() {
    val providers = remember {
        mutableStateListOf(
            // Default
            ProviderInfo(
                authority = "muzei:default",
                packageName = "",
                title = "Muzei",
                description = "A new painting every day",
                currentArtworkUri = "0".toUri(),
                icon = Color.Magenta.toArgb().toDrawable(),
                setupActivity = null,
                settingsActivity = null,
                selected = false
            ),
            // No description
            ProviderInfo(
                authority = "muzei:no_description",
                packageName = "",
                title = "Muzei",
                description = null,
                currentArtworkUri = "0".toUri(),
                icon = Color.Magenta.toArgb().toDrawable(),
                setupActivity = null,
                settingsActivity = null,
                selected = false
            ),
            // No currentArtworkUri
            ProviderInfo(
                authority = "muzei:no_artwork",
                packageName = "",
                title = "Muzei",
                description = "A new painting every day",
                currentArtworkUri = null,
                icon = Color.Magenta.toArgb().toDrawable(),
                setupActivity = null,
                settingsActivity = null,
                selected = false
            ),
            // No description or currentArtworkUri
            ProviderInfo(
                authority = "muzei_empty",
                packageName = "",
                title = "Muzei",
                description = null,
                currentArtworkUri = null,
                icon = Color.Magenta.toArgb().toDrawable(),
                setupActivity = null,
                settingsActivity = null,
                selected = false
            ),
        )
    }
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        AppTheme(
            dynamicColor = false
        ) {
            ChooseProvider(
                providers = providers,
            )
        }
    }
}
