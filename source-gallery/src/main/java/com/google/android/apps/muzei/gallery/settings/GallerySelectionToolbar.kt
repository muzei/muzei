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

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.gallery.R
import com.google.android.apps.muzei.gallery.theme.GalleryTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GallerySelectionToolbar(
    selectionCount: Int,
    title: String,
    modifier: Modifier = Modifier,
    onReset: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val visible = selectionCount > 0
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.wrapContentHeight(align = Alignment.Top),
        enter = slideInVertically { -it },
        exit = slideOutVertically { -it },
        label = "GallerySelectionToolbar",
    ) {
        BackHandler(onBack = onReset)
        TopAppBar(
            title = { Text(text = title) },
            navigationIcon = {
                IconButton(onClick = onReset) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null
                    )
                }
            },
            actions = {
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.RemoveCircle,
                        contentDescription = stringResource(R.string.gallery_action_remove)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        )
    }
}

@Preview
@Composable
fun GallerySelectionToolbarPreview() {
    GalleryTheme(
        dynamicColor = false
    ) {
        GallerySelectionToolbar(
            selectionCount = 5,
            title = "5",
            modifier = Modifier.size(width = 400.dp, height = 400.dp)
        )
    }
}