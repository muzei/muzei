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

package com.google.android.apps.muzei.gallery.settings

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.gallery.R
import com.google.android.apps.muzei.gallery.theme.GalleryTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryTopAppBar(
    importPhotoCount: Int,
    importPhotoTitle: String,
    photoCount: Int,
    modifier: Modifier = Modifier,
    scrollBehavior: TopAppBarScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        rememberTopAppBarState()
    ),
    onImportPhotos: () -> Unit = {},
    onClearPhotos: () -> Unit = {},
) {
    TopAppBar(
        title = { Text(text = stringResource(R.string.gallery_title)) },
        modifier = modifier.wrapContentHeight(align = Alignment.Top),
        actions = {
            if (importPhotoCount == 0 && photoCount == 0) {
                // No menu items to display
                return@TopAppBar
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
                // Make sure the 'Import photos' MenuItem is set up properly based on the number of
                // activities that handle ACTION_GET_CONTENT
                // 0 = hide the MenuItem
                // 1 = show 'Import photos from APP_NAME' to go to the one app that exists
                // 2 = show 'Import photos...' to have the user pick which app to import photos from
                if (importPhotoCount > 0) {
                    DropdownMenuItem(
                        text = { Text(importPhotoTitle) },
                        onClick = {
                            expanded = false
                            onImportPhotos()
                        }
                    )
                }
                if (photoCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.gallery_action_clear_photos)) },
                        onClick = {
                            expanded = false
                            onClearPhotos()
                        }
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun GalleryTopAppBarPreview() {
    GalleryTheme(
        dynamicColor = false
    ) {
        GalleryTopAppBar(
            importPhotoCount = 2,
            stringResource(R.string.gallery_action_import_photos),
            photoCount = 5,
            modifier = Modifier.size(width = 400.dp, height = 400.dp),
        )
    }
}