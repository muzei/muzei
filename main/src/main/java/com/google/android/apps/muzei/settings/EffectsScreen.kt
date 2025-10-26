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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.render.MuzeiBlurRenderer
import com.google.android.apps.muzei.theme.AppTheme
import net.nurik.roman.muzei.R
import kotlin.math.max

@Composable
fun EffectsScreen(
    prefs: SharedPreferences,
    blurPref: String,
    dimPref: String,
    greyPref: String,
    modifier: Modifier = Modifier,
) {
    val blur =
        rememberPreferenceSourcedValue(prefs, blurPref, MuzeiBlurRenderer.DEFAULT_BLUR)
    val dim =
        rememberPreferenceSourcedValue(prefs, dimPref, MuzeiBlurRenderer.DEFAULT_MAX_DIM)
    val grey =
        rememberPreferenceSourcedValue(prefs, greyPref, MuzeiBlurRenderer.DEFAULT_GREY)
    EffectsScreen(
        blur = blur.value,
        onBlurChange = {
            blur.value = it
        },
        onBlurChangeFinished = {
            blur.userControlled = false
        },
        dim = dim.value,
        onDimChange = {
            dim.value = it
        },
        onDimChangeFinished = {
            dim.userControlled = false
        },
        grey = grey.value,
        onGreyChange = {
            grey.value = it
        },
        onGreyChangeFinished = {
            grey.userControlled = false
        },
        modifier = modifier
    )
}

@Composable
fun EffectsScreen(
    blur: Int,
    onBlurChange: (Int) -> Unit,
    onBlurChangeFinished: (() -> Unit),
    dim: Int,
    onDimChange: (Int) -> Unit,
    onDimChangeFinished: (() -> Unit),
    grey: Int,
    onGreyChange: (Int) -> Unit,
    onGreyChangeFinished: (() -> Unit),
    modifier: Modifier = Modifier,
) {
    val windowSize = with(LocalDensity.current) {
        val pixelSize = LocalWindowInfo.current.containerSize
        DpSize(pixelSize.width.toDp(), pixelSize.height.toDp())
    }
    if (windowSize.width >= 600.dp && windowSize.height >= 600.dp) {
        EffectsGrid(
            modifier = modifier
                .wrapContentSize()
                .sizeIn(maxWidth = 500.dp),
            blur = blur,
            onBlurChange = onBlurChange,
            onBlurChangeFinished = onBlurChangeFinished,
            dim = dim,
            onDimChange = onDimChange,
            onDimChangeFinished = onDimChangeFinished,
            grey = grey,
            onGreyChange = onGreyChange,
            onGreyChangeFinished = onGreyChangeFinished,
        )
    } else {
        EffectsGrid(
            modifier = modifier
                .wrapContentHeight()
                .padding(horizontal = 32.dp),
            blur = blur,
            onBlurChange = onBlurChange,
            onBlurChangeFinished = onBlurChangeFinished,
            dim = dim,
            onDimChange = onDimChange,
            onDimChangeFinished = onDimChangeFinished,
            grey = grey,
            onGreyChange = onGreyChange,
            onGreyChangeFinished = onGreyChangeFinished,
        )
    }
}

@Composable
private fun EffectsGrid(
    modifier: Modifier = Modifier,
    blur: Int = MuzeiBlurRenderer.DEFAULT_BLUR,
    onBlurChange: (Int) -> Unit = {},
    onBlurChangeFinished: (() -> Unit) = {},
    dim: Int = MuzeiBlurRenderer.DEFAULT_MAX_DIM,
    onDimChange: (Int) -> Unit = {},
    onDimChangeFinished: (() -> Unit) = {},
    grey: Int = MuzeiBlurRenderer.DEFAULT_GREY,
    onGreyChange: (Int) -> Unit = {},
    onGreyChangeFinished: (() -> Unit) = {},
) {
    Layout(
        content = {
            // Titles
            Text(
                text = stringResource(R.string.settings_blur_amount_title),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_dim_amount_title),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.settings_grey_amount_title),
                modifier = Modifier.padding(vertical = 8.dp),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            // Sliders
            Slider(
                value = blur.toFloat(),
                onValueChange = { onBlurChange(it.toInt()) },
                modifier = Modifier.padding(vertical = 8.dp),
                valueRange = 0f..500f,
                onValueChangeFinished = onBlurChangeFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.DarkGray,
                )
            )
            Slider(
                value = dim.toFloat(),
                onValueChange = { onDimChange(it.toInt()) },
                modifier = Modifier.padding(vertical = 8.dp),
                valueRange = 0f..255f,
                onValueChangeFinished = onDimChangeFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.DarkGray,
                )
            )
            Slider(
                value = grey.toFloat(),
                onValueChange = { onGreyChange(it.toInt()) },
                modifier = Modifier.padding(vertical = 8.dp),
                valueRange = 0f..500f,
                onValueChangeFinished = onGreyChangeFinished,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.DarkGray,
                )
            )
        },
        modifier = modifier
    ) { measurables, constraints ->
        val titlePlaceables = measurables.take(3).map { measurable ->
            measurable.measure(constraints.copy(minWidth = 0))
        }
        val maxTitleWidth = titlePlaceables.maxOf { it.width }
        val spacing = 16.dp.roundToPx()
        val sliderPlaceables = measurables.takeLast(3).map { measurable ->
            measurable.measure(
                constraints.copy(
                    minWidth = constraints.maxWidth - maxTitleWidth - spacing,
                    maxWidth = constraints.maxWidth - maxTitleWidth - spacing,
                )
            )
        }
        val columnHeights = titlePlaceables.mapIndexed { index, titlePlaceable ->
            max(titlePlaceable.height, sliderPlaceables[index].height)
        }
        val maxColumnHeight = columnHeights.max()

        val layoutWidth = constraints.maxWidth
        val layoutHeight = maxColumnHeight * 3
        layout(layoutWidth, layoutHeight) {
            titlePlaceables.forEachIndexed { index, titlePlaceable ->
                val height = titlePlaceable.height
                titlePlaceable.placeRelative(
                    0,
                    index * maxColumnHeight + (maxColumnHeight - height) / 2
                )
            }
            sliderPlaceables.forEachIndexed { index, sliderPlaceable ->
                val height = sliderPlaceable.height
                sliderPlaceable.placeRelative(
                    maxTitleWidth + spacing,
                    index * maxColumnHeight + (maxColumnHeight - height) / 2
                )
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
@Composable
fun EffectsScreenPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        var blur by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_BLUR) }
        var dim by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_MAX_DIM) }
        var grey by remember { mutableIntStateOf(MuzeiBlurRenderer.DEFAULT_GREY) }
        EffectsScreen(
            blur = blur,
            onBlurChange = { blur = it },
            onBlurChangeFinished = {},
            dim = dim,
            onDimChange = { dim = it },
            onDimChangeFinished = {},
            grey = grey,
            onGreyChange = { grey = it },
            onGreyChangeFinished = {},
            modifier = Modifier.fillMaxSize(),
        )
    }
}