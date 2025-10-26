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

package com.google.android.apps.muzei.settings

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.edit
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.theme.AppTheme
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EffectsSettings(
    prefs: SharedPreferences,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit) = {},
    pagerState: PagerState = rememberPagerState(
        initialPage = 0,
        pageCount = { 2 },
    ),
) {
    @Suppress("SpellCheckingInspection")
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = {},
                    navigationIcon = navigationIcon,
                    actions = { EffectsSettingsActions(prefs, snackbarHostState, pagerState) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    )
                )
                SecondaryTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(
                                pagerState.currentPage,
                                matchContentSize = false
                            ),
                            color = Color.White,
                        )
                    }
                ) {
                    val coroutineScope = rememberCoroutineScope()
                    val tabs = listOf(
                        stringResource(R.string.settings_home_screen_title),
                        stringResource(R.string.settings_lock_screen_title),
                    )
                    tabs.forEachIndexed { index, tabTitle ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = {
                                Text(text = tabTitle)
                            },
                        )
                    }
                }
            }
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState)
        },
        containerColor = Color.Transparent,
        contentColor = Color.White,
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(innerPadding),
        ) { page ->
            val blurPref = if (page == 0) Prefs.PREF_BLUR_AMOUNT else Prefs.PREF_LOCK_BLUR_AMOUNT
            val dimPref = if (page == 0) Prefs.PREF_DIM_AMOUNT else Prefs.PREF_LOCK_DIM_AMOUNT
            val greyPref = if (page == 0) Prefs.PREF_GREY_AMOUNT else Prefs.PREF_LOCK_GREY_AMOUNT
            EffectsScreen(
                prefs = prefs,
                blurPref = blurPref,
                dimPref = dimPref,
                greyPref = greyPref,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun EffectsSettingsActions(
    prefs: SharedPreferences,
    @Suppress("SpellCheckingInspection")
    snackbarHostState: SnackbarHostState,
    pagerState: PagerState,
) {
    val coroutineScope = rememberCoroutineScope()
    val linked = rememberPreferenceSourcedValue(
        prefs,
        Prefs.PREF_LINK_EFFECTS,
        false,
    )
    if (linked.value) {
        // Keep the home screen and lock screen effects in sync
        LaunchedEffect(Unit) {
            launch {
                // Update the lock screen effect to match the updated home screen
                prefs.callbackFlow(
                    Prefs.PREF_BLUR_AMOUNT,
                    MuzeiBlurRenderer.DEFAULT_BLUR,
                    getValue = { prefs, prefName, defaultValue ->
                        prefs.getInt(prefName, defaultValue)
                    }
                ).collect { newValue ->
                    prefs.edit {
                        putInt(Prefs.PREF_LOCK_BLUR_AMOUNT, newValue)
                    }
                }
            }
            launch {
                // Update the home screen effect to match the updated lock screen
                prefs.callbackFlow(
                    Prefs.PREF_LOCK_BLUR_AMOUNT,
                    MuzeiBlurRenderer.DEFAULT_BLUR,
                    getValue = { prefs, prefName, defaultValue ->
                        prefs.getInt(prefName, defaultValue)
                    }
                ).collect { newValue ->
                    prefs.edit {
                        putInt(Prefs.PREF_BLUR_AMOUNT, newValue)
                    }
                }
            }
            launch {
                // Update the lock screen effect to match the updated home screen
                prefs.callbackFlow(
                    Prefs.PREF_DIM_AMOUNT,
                    MuzeiBlurRenderer.DEFAULT_MAX_DIM,
                    getValue = { prefs, prefName, defaultValue ->
                        prefs.getInt(prefName, defaultValue)
                    }
                ).collect { newValue ->
                    prefs.edit {
                        putInt(Prefs.PREF_LOCK_DIM_AMOUNT, newValue)
                    }
                }
            }
            launch {
                // Update the home screen effect to match the updated lock screen
                prefs.callbackFlow(
                    Prefs.PREF_LOCK_DIM_AMOUNT,
                    MuzeiBlurRenderer.DEFAULT_MAX_DIM,
                    getValue = { prefs, prefName, defaultValue ->
                        prefs.getInt(prefName, defaultValue)
                    }
                ).collect { newValue ->
                    prefs.edit {
                        putInt(Prefs.PREF_DIM_AMOUNT, newValue)
                    }
                }
            }
            launch {
                // Update the lock screen effect to match the updated home screen
                prefs.callbackFlow(
                    Prefs.PREF_GREY_AMOUNT,
                    MuzeiBlurRenderer.DEFAULT_GREY,
                    getValue = { prefs, prefName, defaultValue ->
                        prefs.getInt(prefName, defaultValue)
                    }
                ).collect { newValue ->
                    prefs.edit {
                        putInt(Prefs.PREF_LOCK_GREY_AMOUNT, newValue)
                    }
                }
            }
            launch {
                // Update the home screen effect to match the updated lock screen
                prefs.callbackFlow(
                    Prefs.PREF_LOCK_GREY_AMOUNT,
                    MuzeiBlurRenderer.DEFAULT_GREY,
                    getValue = { prefs, prefName, defaultValue ->
                        prefs.getInt(prefName, defaultValue)
                    }
                ).collect { newValue ->
                    prefs.edit {
                        putInt(Prefs.PREF_GREY_AMOUNT, newValue)
                    }
                }
            }
        }
    }

    @Suppress("SpellCheckingInspection")
    val snackbarTitleOnLinked = stringResource(R.string.toast_link_effects)

    @Suppress("SpellCheckingInspection")
    val snackbarTitleOffLinked = stringResource(R.string.toast_link_effects_off)
    IconButton(onClick = {
        val effectsLinked = !linked.value
        prefs.edit {
            putBoolean(Prefs.PREF_LINK_EFFECTS, effectsLinked)
        }
        if (effectsLinked) {
            if (pagerState.currentPage == 0) {
                // Update the lock screen effects to match the home screen
                prefs.edit {
                    putInt(
                        Prefs.PREF_LOCK_BLUR_AMOUNT,
                        prefs.getInt(
                            Prefs.PREF_BLUR_AMOUNT,
                            MuzeiBlurRenderer.DEFAULT_BLUR
                        )
                    )
                    putInt(
                        Prefs.PREF_LOCK_DIM_AMOUNT,
                        prefs.getInt(
                            Prefs.PREF_DIM_AMOUNT,
                            MuzeiBlurRenderer.DEFAULT_MAX_DIM
                        )
                    )
                    putInt(
                        Prefs.PREF_LOCK_GREY_AMOUNT,
                        prefs.getInt(
                            Prefs.PREF_GREY_AMOUNT,
                            MuzeiBlurRenderer.DEFAULT_GREY
                        )
                    )
                }
            } else {
                // Update the home screen effects to match the lock screen
                prefs.edit {
                    putInt(
                        Prefs.PREF_BLUR_AMOUNT,
                        prefs.getInt(
                            Prefs.PREF_LOCK_BLUR_AMOUNT,
                            MuzeiBlurRenderer.DEFAULT_BLUR
                        )
                    )
                    putInt(
                        Prefs.PREF_DIM_AMOUNT,
                        prefs.getInt(
                            Prefs.PREF_LOCK_DIM_AMOUNT,
                            MuzeiBlurRenderer.DEFAULT_MAX_DIM
                        )
                    )
                    putInt(
                        Prefs.PREF_GREY_AMOUNT,
                        prefs.getInt(
                            Prefs.PREF_LOCK_GREY_AMOUNT,
                            MuzeiBlurRenderer.DEFAULT_GREY
                        )
                    )
                }
            }
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = snackbarTitleOnLinked,
                    duration = SnackbarDuration.Long,
                )
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    message = snackbarTitleOffLinked,
                    duration = SnackbarDuration.Long,
                )
            }
        }
    }) {
        Icon(
            if (linked.value) Icons.Default.Link else Icons.Default.LinkOff,
            contentDescription = if (linked.value) {
                stringResource(R.string.action_link_effects)
            } else {
                stringResource(R.string.action_link_effects_off)
            }
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
            text = { Text(text = stringResource(R.string.reset_advanced_settings_defaults)) },
            onClick = {
                expanded = false
                prefs.edit {
                    putInt(
                        Prefs.PREF_BLUR_AMOUNT,
                        MuzeiBlurRenderer.DEFAULT_BLUR
                    )
                    putInt(
                        Prefs.PREF_DIM_AMOUNT,
                        MuzeiBlurRenderer.DEFAULT_MAX_DIM
                    )
                    putInt(
                        Prefs.PREF_GREY_AMOUNT,
                        MuzeiBlurRenderer.DEFAULT_GREY
                    )
                    putInt(
                        Prefs.PREF_LOCK_BLUR_AMOUNT,
                        MuzeiBlurRenderer.DEFAULT_BLUR
                    )
                    putInt(
                        Prefs.PREF_LOCK_DIM_AMOUNT,
                        MuzeiBlurRenderer.DEFAULT_MAX_DIM
                    )
                    putInt(
                        Prefs.PREF_LOCK_GREY_AMOUNT,
                        MuzeiBlurRenderer.DEFAULT_GREY
                    )
                }
            }
        )
    }
}


@Preview(name = "Portrait", device = PHONE)
@Preview(
    name = "Landscape",
    device = "spec:width=411dp,height=891dp,orientation=landscape,dpi=420",
)
@Preview(name = "Tablet - Landscape", device = TABLET)
@Composable
fun EffectsSettingsPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        EffectsSettings(
            prefs = FakeSharedPreferences(),
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Blue),
        )
    }
}