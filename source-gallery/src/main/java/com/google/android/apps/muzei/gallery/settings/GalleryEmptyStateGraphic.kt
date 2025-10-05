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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.google.android.apps.muzei.gallery.theme.GalleryTheme
import kotlinx.coroutines.delay
import kotlin.random.Random

private val BITMAP = intArrayOf(
    0, 0, 1, 1, 1, 1, 0, 0,
    1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 0, 0, 1, 1, 1,
    1, 1, 0, 1, 1, 0, 1, 1,
    1, 1, 0, 1, 1, 0, 1, 1,
    1, 1, 1, 0, 0, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1
)

private const val COLS = 8
private val ROWS = BITMAP.size / COLS

@Composable
fun GalleryEmptyStateGraphic() {
    val cellSize = 8.dp
    val cellSpacing = 2.dp
    val onColor = MaterialTheme.colorScheme.inversePrimary
    val offColor = MaterialTheme.colorScheme.primary
    val onX = remember {
        mutableIntStateOf(0)
    }
    val onY = remember {
        mutableIntStateOf(0)
    }
    val fadeAlpha = remember {
        Animatable(0f)
    }
    LaunchedEffect(Unit) {
        while (true) {
            // Select a new random cell to show as 'on'
            while (true) {
                val newOnX = Random.nextInt(COLS)
                val newOnY = Random.nextInt(ROWS)
                if (newOnX != onX.intValue && newOnY != onY.intValue && BITMAP[newOnY * COLS + newOnX] != 0) {
                    onX.intValue = newOnX
                    onY.intValue = newOnY
                    break
                }
            }
            // Fade in the newly 'on' cell
            fadeAlpha.animateTo(1f, animationSpec = tween(100))
            // Keep it on for a bit
            delay(400L)
            // Then fade it out
            fadeAlpha.animateTo(0f, animationSpec = tween(100))
            // And then turn every cell off for a bit
            delay(50L)
        }
    }
    Canvas(
        modifier = Modifier.size(
            width = COLS * cellSize + (COLS - 1) * cellSpacing,
            height = ROWS * cellSize + (ROWS - 1) * cellSpacing
        )
    ) {
        val cellSize = cellSize.toPx()
        val cellSpacing = cellSpacing.toPx()
        val cellRounding = 1.dp.toPx()
        for (y in 0 until ROWS) {
            for (x in 0 until COLS) {
                val bit = BITMAP[y * COLS + x]
                if (bit == 0) continue
                drawRoundRect(
                    color = offColor,
                    topLeft = Offset(x * (cellSize + cellSpacing), y * (cellSize + cellSpacing)),
                    size = Size(cellSize, cellSize),
                    cornerRadius = CornerRadius(cellRounding),
                    alpha = 1f
                )
                if (x == onX.intValue && y == onY.intValue) {
                    drawRoundRect(
                        color = onColor,
                        topLeft = Offset(
                            x * (cellSize + cellSpacing),
                            y * (cellSize + cellSpacing)
                        ),
                        size = Size(cellSize, cellSize),
                        cornerRadius = CornerRadius(cellRounding),
                        alpha = fadeAlpha.value
                    )
                }

            }
        }
    }
}

@Preview
@Composable
fun GalleryEmptyStateGraphicPreview() {
    GalleryTheme(
        dynamicColor = false
    ) {
        GalleryEmptyStateGraphic()
    }
}