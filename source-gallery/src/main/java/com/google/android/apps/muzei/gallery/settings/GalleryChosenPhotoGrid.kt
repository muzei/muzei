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

import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.google.android.apps.muzei.gallery.ChosenPhoto
import com.google.android.apps.muzei.gallery.theme.GalleryTheme

data class TouchInfo(
    val photo: ChosenPhoto,
    val offset: Offset,
)

@Composable
fun GalleryChosenPhotoGrid(
    photos: List<ChosenPhoto>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    checkedItemIds: Set<Long> = emptySet(),
    onCheckedToggled: (ChosenPhoto) -> Unit = {},
    imageProvider: (chosenPhoto: ChosenPhoto, maxImages: Int) -> List<Uri>,
) {
    GalleryChosenPhotoGrid(
        photos.size,
        keyProvider = { index -> photos[index].uri },
        photoProvider = { index -> photos[index] },
        modifier,
        contentPadding,
        checkedItemIds,
        onCheckedToggled,
        imageProvider
    )
}

@Composable
fun GalleryChosenPhotoGrid(
    photos: LazyPagingItems<ChosenPhoto>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    checkedItemIds: Set<Long> = emptySet(),
    onCheckedToggled: (ChosenPhoto) -> Unit = {},
    imageProvider: (chosenPhoto: ChosenPhoto, maxImages: Int) -> List<Uri>,
) {
    val keyProvider = photos.itemKey { it.uri }
    GalleryChosenPhotoGrid(
        photos.itemCount,
        // Workaround for https://issuetracker.google.com/issues/372311615
        keyProvider = { index -> if (index < photos.itemSnapshotList.size) keyProvider(index) else -1 },
        photoProvider = { index -> photos[index]!! },
        modifier,
        contentPadding,
        checkedItemIds,
        onCheckedToggled,
        imageProvider
    )
}

@Composable
fun GalleryChosenPhotoGrid(
    photoCount: Int,
    keyProvider: (Int) -> Any,
    photoProvider: (Int) -> ChosenPhoto,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    checkedItemIds: Set<Long> = emptySet(),
    onCheckedToggled: (ChosenPhoto) -> Unit = {},
    imageProvider: (ChosenPhoto, Int) -> List<Uri>,
) {
    var lastTouchInfo by remember { mutableStateOf<TouchInfo?>(null) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        items(
            photoCount,
            key = keyProvider
        ) { index ->
            val photo = photoProvider(index)
            val isChecked = photo.id in checkedItemIds
            GalleryChosenPhotoItem(
                chosenPhoto = photo,
                modifier = Modifier
                    .animateItem()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = { offset ->
                                lastTouchInfo = TouchInfo(photo, offset)
                            },
                            onTap = {
                                onCheckedToggled(photo)
                            }
                        )
                    },
                checked = isChecked,
                touchLocation = if (lastTouchInfo?.photo == photo) lastTouchInfo?.offset else null,
                imageProvider = imageProvider
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Preview
@Composable
fun GalleryChosenPhotoGridPreview() {
    val colors = buildList {
        addAll(listOf(Color.Cyan, Color.DarkGray, Color.Magenta, Color.LightGray))
        repeat(10) {
            addAll(listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow))
        }
    }
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        GalleryTheme(
            dynamicColor = false,
        ) {
            GalleryChosenPhotoGrid(
                photos = colors.drop(4).mapIndexed { index, color ->
                    ChosenPhoto(
                        uri = (index + 4).toString().toUri(),
                        isTreeUri = (index + 1) % 5 == 0
                    )
                },
                imageProvider = { _, _ -> listOf("0".toUri(), "1".toUri(), "2".toUri()) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalCoilApi::class)
@Preview
@Composable
fun GalleryChosenPhotoGridCheckedPreview() {
    val colors = buildList {
        addAll(listOf(Color.Cyan, Color.DarkGray, Color.Magenta, Color.LightGray))
        repeat(10) {
            addAll(listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow))
        }
    }
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        GalleryTheme(
            dynamicColor = false,
        ) {
            GalleryChosenPhotoGrid(
                photos = colors.drop(4).mapIndexed { index, color ->
                    ChosenPhoto(
                        uri = (index + 4).toString().toUri(),
                        isTreeUri = (index + 1) % 5 == 0
                    ).apply {
                        id = index.toLong()
                    }
                },
                checkedItemIds = buildSet {
                    colors.forEachIndexed { index, _ ->
                        if (index == 0 || (index + 1) % 7 == 0) {
                            add(index.toLong())
                        }
                    }
                },
                imageProvider = { _, _ -> listOf("0".toUri(), "1".toUri(), "2".toUri()) },
            )
        }
    }
}