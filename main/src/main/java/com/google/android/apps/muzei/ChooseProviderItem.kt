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

package com.google.android.apps.muzei

import android.content.ComponentName
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import androidx.core.net.toUri
import coil3.ColorImage
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.rememberDrawablePainter
import net.nurik.roman.muzei.R

@Composable
fun ChooseProviderItem(
    providerInfo: ProviderInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onBrowseClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier,
    ) {
        // Title Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Max)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // App Icon
            val icon = rememberDrawablePainter(drawable = providerInfo.icon)
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                contentScale = ContentScale.Crop,
            )
            // App Title
            Text(
                text = providerInfo.title,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            // Selected icon
            AnimatedContent(
                providerInfo.selected,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "selected",
            ) { selected ->
                if (selected) {
                    Icon(
                        Icons.Default.Done,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )
                } else {
                    Spacer(modifier = Modifier.size(40.dp))
                }
            }
        }
        // Artwork image
        val emptyColor = MaterialTheme.colorScheme.surfaceDim
        val placeholderPainter = remember(emptyColor) {
            ColorPainter(emptyColor)
        }
        AnimatedContent(
            providerInfo.currentArtworkUri,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "currentArtworkUri",
        ) { artworkUri ->
            if (artworkUri != null) {
                AsyncImage(
                    model = artworkUri,
                    contentDescription = null,
                    modifier = modifier
                        .aspectRatio(16f / 9f),
                    placeholder = placeholderPainter,
                    contentScale = ContentScale.Crop,
                )
            }
        }
        // Description
        AnimatedContent(
            providerInfo.description,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "description",
        ) { description ->
            if (description != null) {
                Text(
                    text = description,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        AnimatedContent(
            providerInfo.selected,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "buttonRow",
        ) { selected ->
            if (selected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnimatedContent(
                        providerInfo.settingsActivity,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "settings",
                    ) { settingsActivity ->
                        if (settingsActivity != null) {
                            TextButton(
                                onClick = onSettingsClick,
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = contentColorFor(MaterialTheme.colorScheme.surfaceContainerHighest),
                                ),
                            ) {
                                Text(text = stringResource(R.string.provider_settings))
                            }
                        }
                    }
                    TextButton(
                        onClick = onBrowseClick,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = contentColorFor(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ),
                    ) {
                        Text(text = stringResource(R.string.provider_browse))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalCoilApi::class)
@Preview(
    device = "spec:width=400dp,height=400dp,orientation=landscape,dpi=420",
)
@Composable
fun ChooseProviderItemPreview(
    @PreviewParameter(ProviderInfoParameterProvider::class) providerInfo: ProviderInfo
) {
    ChooseProviderItemPreviewContent(providerInfo)
}

@OptIn(ExperimentalCoilApi::class)
@Preview(
    device = "spec:width=400dp,height=400dp,orientation=landscape,dpi=420",
)
@Composable
fun ChooseProviderItemSelectedPreview(
    @PreviewParameter(SelectedProviderInfoParameterProvider::class) providerInfo: ProviderInfo
) {
    ChooseProviderItemPreviewContent(providerInfo)
}

@Composable
@OptIn(ExperimentalCoilApi::class)
private fun ChooseProviderItemPreviewContent(providerInfo: ProviderInfo) {
    val colors = listOf(Color.Red, Color.Green, Color.Blue, Color.Yellow)
    val previewHandler = AsyncImagePreviewHandler { request ->
        ColorImage(colors[request.data.toString().toInt().mod(colors.size)].toArgb())
    }
    CompositionLocalProvider(LocalAsyncImagePreviewHandler provides previewHandler) {
        AppTheme(
            dynamicColor = false,
        ) {
            var currentProviderInfo by remember { mutableStateOf(providerInfo) }
            ChooseProviderItem(
                providerInfo = currentProviderInfo,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    currentProviderInfo = currentProviderInfo.copy(
                        selected = !currentProviderInfo.selected
                    )
                },
            )
        }
    }
}

private class ProviderInfoParameterProvider : PreviewParameterProvider<ProviderInfo> {
    override val values = sequenceOf(
        // Default
        ProviderInfo(
            authority = "",
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
            authority = "",
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
            authority = "",
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
            authority = "",
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

private class SelectedProviderInfoParameterProvider : PreviewParameterProvider<ProviderInfo> {
    override val values = sequenceOf(

        // Default
        ProviderInfo(
            authority = "",
            packageName = "",
            title = "Muzei",
            description = "A new painting every day",
            currentArtworkUri = "0".toUri(),
            icon = Color.Magenta.toArgb().toDrawable(),
            setupActivity = null,
            settingsActivity = ComponentName("", ""),
            selected = true
        ),
        // No description
        ProviderInfo(
            authority = "",
            packageName = "",
            title = "Muzei",
            description = null,
            currentArtworkUri = "0".toUri(),
            icon = Color.Magenta.toArgb().toDrawable(),
            setupActivity = null,
            settingsActivity = null,
            selected = true
        ),
        // No currentArtworkUri
        ProviderInfo(
            authority = "",
            packageName = "",
            title = "Muzei",
            description = "A new painting every day",
            currentArtworkUri = null,
            icon = Color.Magenta.toArgb().toDrawable(),
            setupActivity = null,
            settingsActivity = ComponentName("", ""),
            selected = true
        ),
        // No description or currentArtworkUri
        ProviderInfo(
            authority = "",
            packageName = "",
            title = "Muzei",
            description = null,
            currentArtworkUri = null,
            icon = Color.Magenta.toArgb().toDrawable(),
            setupActivity = null,
            settingsActivity = ComponentName("", ""),
            selected = true
        ),
    )
}