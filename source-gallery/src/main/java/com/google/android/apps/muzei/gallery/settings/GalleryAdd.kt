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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import com.google.android.apps.muzei.gallery.R
import com.google.android.apps.muzei.gallery.theme.GalleryTheme
import kotlinx.serialization.Serializable

@Serializable
sealed class AddMode

@Serializable
data object AddFab : AddMode()

@Serializable
data object AddToolbar : AddMode()

@Serializable
data object AddNone : AddMode()

@Composable
fun GalleryAdd(
    mode: AddMode,
    modifier: Modifier = Modifier,
    onToggleToolbar: () -> Unit = {},
    onAddPhoto: () -> Unit = {},
    onAddFolder: () -> Unit = {},
) {
    var currentMode by rememberSerializable(
        serializer = MutableStateSerializer()
    ) {
        mutableStateOf(mode)
    }
    val fabVisibility = remember { SeekableTransitionState(mode == AddFab) }
    val fabTransition = rememberTransition(fabVisibility, "AddFabVisibility")
    val toolbarRevealProgress = remember { Animatable(if (mode == AddToolbar) 1f else 0f) }
    val toolbarRevealPath = remember { Path() }
    val stiffness = if (currentMode != AddNone && mode != AddNone) {
        // Increase the stiffness if we are doing two sequential
        // animations (e.g., from AddFab to AddToolbar or vice versa)
        Spring.StiffnessHigh
    } else {
        // Use a lower stiffness if doing a single animation to AddNone
        Spring.StiffnessMedium
    }
    LaunchedEffect(currentMode, mode) {
        if (currentMode == mode) {
            return@LaunchedEffect
        }
        // First animate elements out
        if (currentMode == AddFab) {
            // Animate out FAB
            fabVisibility.animateTo(false)
        }
        if (currentMode == AddToolbar) {
            // Animate out toolbar
            toolbarRevealProgress.animateTo(
                0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = stiffness,
                )
            )
        }
        // Now animate in new elements
        if (mode == AddFab) {
            // Animate in FAB
            fabVisibility.animateTo(true)
        }
        if (mode == AddToolbar) {
            // Animate in toolbar
            toolbarRevealProgress.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = stiffness,
                )
            )
        }
        currentMode = mode
    }
    Box(modifier) {
        if (currentMode == AddFab || mode == AddFab) {
            val offset = with(LocalDensity.current) {
                IntOffset(0, 16.dp.toPx().toInt())
            }
            fabTransition.AnimatedVisibility(
                visible = { it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
                enter = scaleIn(
                    animationSpec = spring(stiffness = stiffness)
                ) + slideIn(
                    animationSpec = spring(stiffness = stiffness)
                ) { offset },
                exit = scaleOut(
                    animationSpec = spring(stiffness = stiffness)
                ) + slideOut(
                    animationSpec = spring(stiffness = stiffness)
                ) { offset },
            ) {
                GalleryAddFloatingActionButton(
                    onToggleToolbar = onToggleToolbar,
                )
            }
        }
        if (currentMode == AddToolbar || mode == AddToolbar) {
            GalleryAddToolbar(
                onToggleToolbar = onToggleToolbar,
                onAddPhoto = onAddPhoto,
                onAddFolder = onAddFolder,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .drawWithContent {
                        toolbarRevealPath.rewind()
                        val center = Offset(size.width / 2f, size.height / 2f)
                        toolbarRevealPath.addOval(
                            Rect(
                                center = center,
                                radius = maxDistanceToCorner(
                                    center,
                                    size
                                ) * toolbarRevealProgress.value
                            )
                        )
                        clipPath(toolbarRevealPath) {
                            this@drawWithContent.drawContent()
                        }
                    },
            )
        }
    }
}

@Composable
private fun GalleryAddFloatingActionButton(
    onToggleToolbar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onToggleToolbar,
        modifier = modifier,
    ) {
        Icon(
            painter = rememberVectorPainter(Icons.Default.Add),
            contentDescription = stringResource(R.string.gallery_add_fab)
        )
    }
}

@Composable
private fun GalleryAddToolbar(
    onToggleToolbar: () -> Unit,
    onAddPhoto: () -> Unit,
    onAddFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onToggleToolbar)
    Surface(
        modifier = modifier
            .height(56.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clickable(onClick = onAddPhoto)
            ) {
                Icon(
                    painter = rememberVectorPainter(Icons.Default.Photo),
                    contentDescription = stringResource(R.string.gallery_add_photos),
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .clickable(onClick = onAddFolder)
            ) {
                Icon(
                    painter = rememberVectorPainter(Icons.Default.Folder),
                    contentDescription = stringResource(R.string.gallery_add_folder),
                    modifier = Modifier
                        .size(24.dp)
                        .align(Alignment.Center)
                )
            }
        }
    }
}

@Preview
@Composable
fun GalleryAddFloatingActionButtonPreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        GalleryAdd(
            mode = AddFab,
            modifier = Modifier.size(width = 400.dp, height = 100.dp),
        )
    }
}

@Preview
@Composable
fun GalleryAddToolbarPreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        GalleryAdd(
            mode = AddToolbar,
            modifier = Modifier.size(width = 400.dp, height = 100.dp),
        )
    }
}


@Preview
@Composable
fun GalleryAddTogglePreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        var mode: AddMode by rememberSerializable(
            serializer = MutableStateSerializer()
        ) {
            mutableStateOf(AddFab)
        }
        val options = listOf(AddFab, AddToolbar, AddNone)
        Column {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = options.size
                        ),
                        onClick = { mode = option },
                        selected = mode == option,
                    ) {
                        Text(text = option::class.simpleName.orEmpty())
                    }
                }
            }
            GalleryAdd(
                mode = mode,
                modifier = Modifier.size(width = 400.dp, height = 100.dp),
            )
        }
    }
}