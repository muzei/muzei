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

import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.plus
import net.nurik.roman.muzei.R
import kotlin.math.max
import net.nurik.roman.muzei.androidclientcommon.R as CommonR

private const val TEXT_ALPHA_START = 500
private const val TEXT_ALPHA_TIME = 250
private const val TRANSLATE_START = 2000
private const val TRANSLATE_TIME = 500

@Composable
fun Tutorial(
    modifier: Modifier = Modifier,
    startWithAnimationComplete: Boolean = false,
    onAnimationCompleted: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    var started by rememberSaveable { mutableStateOf(startWithAnimationComplete) }
    val transition = updateTransition(started, label = "Tutorial")
    val textAlphaProgress by transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(TEXT_ALPHA_TIME, delayMillis = TEXT_ALPHA_START, easing = EaseOut)
                false -> tween(0)
            }
        }
    ) { state ->
        if (state) 1f else 0f
    }
    val iconAlphaProgress by transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(TRANSLATE_TIME, delayMillis = TRANSLATE_START, easing = EaseOut)
                false -> tween(0)
            }
        }
    ) { state ->
        if (state) 1f else 0f
    }
    val iconOffset = 100.dp
    val iconOffsetProgress by transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(
                    TRANSLATE_TIME,
                    delayMillis = TRANSLATE_START,
                    easing = EaseOutBack
                )

                false -> tween(0)
            }
        }
    ) { state ->
        if (state) 0f else with(LocalDensity.current) { iconOffset.toPx() }
    }
    val textOffset = 20.dp
    val textOffsetProgress by transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(
                    TRANSLATE_TIME,
                    delayMillis = TRANSLATE_START,
                    easing = EaseOutBack
                )

                false -> tween(0)
            }
        }
    ) { state ->
        if (state) 0f else with(LocalDensity.current) { textOffset.toPx() }
    }
    LaunchedEffect(started) {
        if (!started) {
            started = true
        }
    }
    val emanateBackground by remember {
        derivedStateOf { started && iconOffsetProgress == 0f }
    }
    LaunchedEffect(emanateBackground) {
        if (emanateBackground) {
            onAnimationCompleted()
        }
    }
    val windowSize = with(LocalDensity.current) {
        val pixelSize = LocalWindowInfo.current.containerSize
        DpSize(pixelSize.width.toDp(), pixelSize.height.toDp())
    }
    if (windowSize.width >= 600.dp && windowSize.height >= 600.dp) {
        Column(
            modifier = modifier
                .wrapContentSize()
                .sizeIn(maxWidth = 360.dp, maxHeight = 400.dp)
        ) {
            TutorialIcon(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .drawWithContent {
                        translate(top = iconOffsetProgress) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .graphicsLayer { alpha = iconAlphaProgress },
                emanateBackground = emanateBackground,
                onClick = onClick
            )
            TutorialText(
                modifier = Modifier
                    .drawWithContent {
                        translate(top = -textOffsetProgress) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .graphicsLayer { alpha = textAlphaProgress }
            )
        }
    } else {
        Box(modifier = modifier) {
            TutorialIcon(
                modifier = Modifier
                    .align(Alignment.Center)
                    .drawWithContent {
                        translate(top = iconOffsetProgress) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .graphicsLayer { alpha = iconAlphaProgress },
                emanateBackground = emanateBackground,
                onClick = onClick
            )
            TutorialText(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomStart)
                    .padding(
                        WindowInsets.safeDrawing.asPaddingValues() +
                                PaddingValues(32.dp)
                    )
                    .drawWithContent {
                        translate(top = -textOffsetProgress) {
                            this@drawWithContent.drawContent()
                        }
                    }
                    .graphicsLayer { alpha = textAlphaProgress }
            )
        }
    }
}

@Composable
private fun TutorialIcon(
    modifier: Modifier = Modifier,
    emanateBackground: Boolean = true,
    onClick: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    Layout(
        content = {
            // Icon background
            var backgroundStarted by remember { mutableStateOf(false) }
            LaunchedEffect(emanateBackground) {
                if (emanateBackground) {
                    backgroundStarted = true
                }
            }
            val background = rememberAnimatedVectorPainter(
                AnimatedImageVector.animatedVectorResource(R.drawable.avd_tutorial_icon_emanate),
                atEnd = backgroundStarted
            )
            Image(
                background,
                contentDescription = null,
                modifier = Modifier.size(144.dp)
            )
            // App Icon
            val icon = painterResource(R.drawable.tutorial_icon)
            Image(
                icon,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                colorFilter = if (isPressed) {
                    ColorFilter.tint(Color(0x80FFFFFF), BlendMode.SrcAtop)
                } else {
                    null
                }
            )
            // App Title
            Text(
                text = stringResource(CommonR.string.app_name),
                modifier = Modifier
                    .wrapContentWidth()
                    .padding(top = 4.dp, bottom = 16.dp),
                color = Color.White,
                fontSize = 16.sp,
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color(0x88000000),
                        offset = Offset(0f, 1f),
                        blurRadius = 3f
                    )
                )
            )
        },
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClickLabel = stringResource(CommonR.string.app_name),
            onClick = onClick,
        ),
    ) { measurables, constraints ->
        val (backgroundPlaceable, iconPlaceable, titlePlaceable) = measurables.map { measurable ->
            // Measure each children
            measurable.measure(constraints)
        }
        val layoutWidth = backgroundPlaceable.width
        val iconY = backgroundPlaceable.height / 2 - iconPlaceable.height / 2
        val layoutHeight = max(
            backgroundPlaceable.height,
            iconY + iconPlaceable.height + titlePlaceable.height - 4.dp.roundToPx()
        )
        layout(layoutWidth, layoutHeight) {
            val backgroundX = 0
            val backgroundY = 0
            backgroundPlaceable.placeRelative(backgroundX, backgroundY)
            val iconX = layoutWidth / 2 - iconPlaceable.width / 2
            iconPlaceable.placeRelative(iconX, iconY)
            val titleX = layoutWidth / 2 - titlePlaceable.width / 2
            val titleY = iconY + iconPlaceable.height - 4.dp.roundToPx()
            titlePlaceable.placeRelative(titleX, titleY)
        }
    }
}

@Composable
private fun TutorialText(
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.tutorial_main),
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            color = Color.White,
            fontSize = 22.sp,
            fontFamily = FontFamily.SansSerif,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0x88000000),
                    offset = Offset(0f, 1f),
                    blurRadius = 3f
                )
            )
        )
        Text(
            text = stringResource(R.string.tutorial_subtitle),
            modifier = Modifier.padding(top = 4.dp),
            color = Color(0xAAFFFFFF),
            fontSize = 16.sp,
            fontFamily = FontFamily.SansSerif,
            style = TextStyle(
                shadow = Shadow(
                    color = Color(0x88000000),
                    offset = Offset(0f, 1f),
                    blurRadius = 3f
                )
            )
        )
    }
}

@Preview
@Composable
fun TutorialIconPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        TutorialIcon()
    }
}

@Preview
@Composable
fun TutorialTextPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        TutorialText(
            Modifier
                .size(400.dp)
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
fun TutorialPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        Tutorial(
            modifier = Modifier.fillMaxSize(),
            startWithAnimationComplete = true,
        )
    }
}