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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush.Companion.linearGradient
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.apps.muzei.render.MuzeiRendererFragment
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.AnimatedMuzeiLogo
import kotlinx.coroutines.delay
import net.nurik.roman.muzei.R

@Composable
fun Intro(
    modifier: Modifier = Modifier,
    background: @Composable () -> Unit = {
        MuzeiRendererFragment(
            demoMode = true,
            demoFocus = false,
            modifier = Modifier.fillMaxSize()
        )
    },
    onActivate: () -> Unit = {},
) {
    val isPreview = LocalInspectionMode.current
    var showActivate by rememberSaveable { mutableStateOf(isPreview) }
    val activateAlpha by animateFloatAsState(
        targetValue = if (showActivate) 1.0f else 0f,
        animationSpec = tween(500),
        label = "activateAlpha",
    )
    Box(modifier = modifier) {
        background()
        Layout(
            content = {
                // Logo
                var started by rememberSaveable { mutableStateOf(isPreview) }
                LaunchedEffect(started) {
                    if (!started) {
                        delay(1000)
                        started = true
                    }
                }
                AnimatedMuzeiLogo(
                    modifier = Modifier
                        .padding(4.dp)
                        .width(225.dp),
                    started = started,
                    onFillStarted = { showActivate = true }
                )
                // Activate
                LargeFloatingActionButton(
                    onClick = onActivate,
                    modifier = Modifier.graphicsLayer { alpha = activateAlpha },
                    shape = CircleShape,
                    containerColor = Color.White,
                    contentColor = Color(0xFF333333),
                ) {
                    Text(
                        text = stringResource(R.string.action_activate),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                    )
                }
            },
            modifier = Modifier
                .background(
                    brush = linearGradient(
                        colors = listOf(Color.Transparent, Color(0xCC000000)),
                        start = Offset(0f, Float.POSITIVE_INFINITY),
                        end = Offset.Zero
                    )
                ),
            measurePolicy = { measurables, constraints ->
                val (logoPlaceable, activatePlaceable) = measurables.map { measurable ->
                    // Measure each children
                    measurable.measure(constraints)
                }
                val layoutWidth = constraints.maxWidth
                val layoutHeight = constraints.maxHeight
                layout(layoutWidth, layoutHeight) {
                    if (layoutHeight > layoutWidth) {
                        // Portrait
                        // Center the logo
                        val logoX = layoutWidth / 2 - logoPlaceable.width / 2
                        val logoY = layoutHeight / 2 - logoPlaceable.height / 2
                        logoPlaceable.placeRelative(logoX, logoY)
                        // Put the activate button below the logo with 48dp spacing
                        val space = 48.dp.roundToPx()
                        val activateX = layoutWidth / 2 - activatePlaceable.width / 2
                        val activateY = logoY + logoPlaceable.height + space
                        activatePlaceable.placeRelative(activateX, activateY)
                    } else {
                        // Landscape
                        // Center both the logo and the activate button with 48dp between them
                        val space = 48.dp.roundToPx()
                        val totalWidth = logoPlaceable.width + activatePlaceable.width + space
                        val logoX = (layoutWidth - totalWidth) / 2
                        val logoY = (layoutHeight - logoPlaceable.height) / 2
                        logoPlaceable.placeRelative(logoX, logoY)
                        val activateX = logoX + logoPlaceable.width + space
                        val activateY = (layoutHeight - activatePlaceable.height) / 2
                        activatePlaceable.placeRelative(activateX, activateY)
                    }
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
fun IntroPortraitPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        Intro(
            modifier = Modifier.fillMaxSize(),
            background = {
                Spacer(
                    modifier = Modifier
                        .background(color = Color.Blue)
                        .fillMaxSize()
                )
            }
        )
    }
}