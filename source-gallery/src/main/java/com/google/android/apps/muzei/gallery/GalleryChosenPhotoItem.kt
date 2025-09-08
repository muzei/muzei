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

package com.google.android.apps.muzei.gallery

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.google.android.apps.muzei.gallery.theme.GalleryTheme
import kotlin.math.hypot
import kotlin.math.max

@Composable
fun GalleryChosenPhotoItem(
    chosenPhoto: ChosenPhoto,
    checked: Boolean = false,
    touchLocation: Offset? = null,
    imageProvider: (chosenPhoto: ChosenPhoto, maxImages: Int) -> List<Uri>,
) {
    Box(
        modifier = Modifier.aspectRatio(1f)
    ) {
        val images = if (chosenPhoto.isTreeUri) {
            imageProvider(chosenPhoto, 4)
        } else {
            listOf(chosenPhoto.uri)
        }
        if (images.size <= 1) {
            // Show the one image taking up the full space
            GalleryImage(
                images.getOrNull(0)
            )
        } else {
            // Scrim for the folder icon
            val scrimColor = MaterialTheme.colorScheme.surfaceVariant
            val scrimBrush = remember {
                Brush.radialGradient(
                    colors = listOf(
                        scrimColor.copy(alpha = 0.75f),
                        Color.Transparent
                    ),
                    center = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                    radius = Float.POSITIVE_INFINITY,
                )
            }
            // Show up to the first four images in a 2x2 grid
            Row(
                modifier = Modifier.drawWithContent {
                    drawContent()
                    drawRect(
                        scrimBrush
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    val imageModifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                    GalleryImage(
                        images.getOrNull(0),
                        modifier = imageModifier
                    )
                    GalleryImage(
                        images.getOrNull(1),
                        modifier = imageModifier
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                ) {
                    val imageModifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                    GalleryImage(
                        images.getOrNull(2),
                        modifier = imageModifier
                    )
                    GalleryImage(
                        images.getOrNull(3),
                        modifier = imageModifier
                    )
                }
            }
            Image(
                Icons.Default.FolderOpen,
                null,
                modifier = Modifier
                    .padding(8.dp)
                    .size(24.dp)
                    .align(Alignment.BottomEnd),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
            )
        }
        val revealProgress = remember { Animatable(if (checked) 1f else 0f) }
        val revealPath = remember { Path() }
        LaunchedEffect(checked, touchLocation) {
            revealProgress.animateTo(if (checked) 1f else 0f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithContent {
                    revealPath.rewind()
                    val center = touchLocation ?: Offset(size.width / 2f, size.height / 2f)
                    revealPath.addOval(
                        Rect(
                            center = center,
                            radius = maxDistanceToCorner(center, size) * revealProgress.value
                        )
                    )
                    clipPath(revealPath) {
                        this@drawWithContent.drawContent()
                    }
                }
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f))
        ) {
            Image(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center),
                colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSurface)
            )
        }
    }
}

private fun maxDistanceToCorner(offset: Offset, size: Size): Float {
    var maxDistance = 0f
    maxDistance = max(maxDistance, hypot((offset.x - 0), (offset.y - 0)))
    maxDistance = max(maxDistance, hypot((offset.x - size.width), (offset.y - 0)))
    maxDistance = max(maxDistance, hypot((offset.x - 0), (offset.y - size.height)))
    maxDistance = max(maxDistance, hypot((offset.x - size.width), (offset.y - size.height)))
    return maxDistance
}

@Composable
private fun GalleryImage(
    uri: Uri?,
    modifier: Modifier = Modifier
) {
    val emptyColor = MaterialTheme.colorScheme.surfaceDim
    if (uri == null) {
        Spacer(
            modifier = modifier
                .background(emptyColor)
        )
    } else {
        val placeholderPainter = remember(emptyColor) {
            ColorPainter(emptyColor)
        }
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = modifier
                .aspectRatio(1f),
            placeholder = placeholderPainter,
            contentScale = ContentScale.Crop,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Preview
@Composable
fun GalleryChosenPhotoItemSinglePreview() {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        GalleryTheme(
            dynamicColor = false,
        ) {
            GalleryChosenPhotoItem(
                ChosenPhoto(
                    "0".toUri(),
                    isTreeUri = false
                ),
                imageProvider = { _, _ -> listOf("0".toUri()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Preview
@Composable
fun GalleryChosenPhotoItemSingleCheckedPreview() {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        GalleryTheme(
            dynamicColor = false,
        ) {
            GalleryChosenPhotoItem(
                ChosenPhoto(
                    "0".toUri(),
                    isTreeUri = false
                ),
                checked = true,
                imageProvider = { _, _ -> listOf("0".toUri()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Preview
@Composable
fun GalleryChosenPhotoItemMultiplePreview() {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        GalleryTheme(
            dynamicColor = false,
        ) {
            GalleryChosenPhotoItem(
                ChosenPhoto(
                    "0".toUri(),
                    isTreeUri = true
                ),
                imageProvider = { _, _ -> listOf("0".toUri(), "1".toUri(), "2".toUri()) },
            )
        }
    }
}