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

package com.google.android.apps.muzei.util

import android.graphics.PathMeasure
import android.util.Log
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect.Companion.dashPathEffect
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.ParseException
import kotlin.math.max

private const val TAG = "AnimatedMuzeiLogo"
private const val TRACE_TIME = 2000
private const val TRACE_TIME_PER_GLYPH = 1000
private const val FILL_START = 1200
private const val FILL_TIME = 2000
private val VIEWPORT = Size(1000f, 300f)

private sealed class LogoAnimationState
private data object NotStarted : LogoAnimationState()
private data class InProgress(
    val glyphTraceProgress: List<State<Float>>,
    val fillProgress: State<Float>,
) : LogoAnimationState()

private data object Completed : LogoAnimationState()

@Composable
fun AnimatedMuzeiLogo(
    modifier: Modifier = Modifier,
    started: Boolean = true,
    onFillStarted: () -> Unit = {},
) {
    val transition = updateTransition(started, label = "AnimatedMuzeiLogo")
    val glyphTraceProgress = LogoPaths.GLYPHS.mapIndexed { index, glyph ->
        key(index) {
            transition.animateFloat(
                transitionSpec = {
                    when (targetState) {
                        true -> {
                            val delayPerGlyph = TRACE_TIME - TRACE_TIME_PER_GLYPH
                            val delay = index.toFloat() / LogoPaths.GLYPHS.size
                            tween(
                                TRACE_TIME_PER_GLYPH,
                                delayMillis = (delayPerGlyph * delay).toInt(),
                                easing = EaseOut
                            )
                        }

                        false -> tween(0)
                    }
                },
                label = "traceProgress[$index]"
            ) { state ->
                if (state) 1f else 0f
            }
        }
    }
    val fillProgress = transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(FILL_TIME, delayMillis = FILL_START, easing = EaseOutCubic)
                false -> tween(0)
            }
        },
        label = "fillProgress"
    ) { state ->
        if (state) 1f else 0f
    }
    val logoOffset = 8.dp
    val offsetProgress by transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(
                    TRACE_TIME - FILL_START,
                    delayMillis = FILL_START,
                    easing = EaseOutBack
                )

                false -> tween(0)
            }
        }
    ) { state ->
        if (state) 0f else with(LocalDensity.current) { logoOffset.toPx() }
    }
    val subtitleAlphaProgress by transition.animateFloat(
        transitionSpec = {
            when (targetState) {
                true -> tween(TRACE_TIME - FILL_START, delayMillis = FILL_START, easing = EaseOut)
                false -> tween(0)
            }
        }
    ) { state ->
        if (state) 1f else 0f
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(-logoOffset / 2),
    ) {
        MuzeiLogo(
            state = if (!transition.currentState && !transition.isRunning) {
                NotStarted
            } else if (transition.currentState != transition.targetState) {
                InProgress(
                    glyphTraceProgress = glyphTraceProgress,
                    fillProgress = fillProgress
                )
            } else {
                Completed
            },
            modifier = Modifier
                .padding(bottom = logoOffset)
                .drawWithContent {
                    translate(top = offsetProgress) {
                        this@drawWithContent.drawContent()
                    }
                },
            onFillStarted = {
                onFillStarted()
            }
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = subtitleAlphaProgress }
                .padding(top = logoOffset / 2)
                .drawWithContent {
                    translate(top = -offsetProgress / 2) {
                        this@drawWithContent.drawContent()
                    }
                },
        ) {
            Text(
                text = "L I V E   W A L L P A P E R",
                color = Color.White,
                autoSize = TextAutoSize.StepBased(stepSize = 0.05.sp),
                softWrap = false,
            )
        }
    }
}

@Composable
private fun MuzeiLogo(
    state: LogoAnimationState,
    modifier: Modifier = Modifier,
    onFillStarted: () -> Unit = {},
) {
    Spacer(
        modifier = modifier
            .aspectRatio(VIEWPORT.width / VIEWPORT.height)
            .drawWithCache {
                val parser = SvgPathParser(
                    transformX = { x -> x * size.width / VIEWPORT.width },
                    transformY = { y -> y * size.height / VIEWPORT.height }
                )
                val paths = LogoPaths.GLYPHS.map { glyph ->
                    try {
                        parser.parsePath(glyph).asComposePath()
                    } catch (e: ParseException) {
                        Log.e(TAG, "Couldn't parse path", e)
                        Path()
                    }
                }
                val pathLength = paths.map { path ->
                    val pm = PathMeasure(path.asAndroidPath(), true)
                    var length = pm.length
                    while (true) {
                        length = max(length, pm.length)
                        if (!pm.nextContour()) {
                            break
                        }
                    }
                    length
                }
                val traceWidth = 1.dp.toPx()
                val traceResidueColor = Color.White.copy(alpha = 0.5f)
                val traceMarkerLength = 16.dp.toPx()
                val traceColor = Color.White
                var isFillStarted = false
                onDrawBehind {
                    when (state) {
                        NotStarted -> {
                            // Show nothing
                        }

                        is InProgress -> {
                            paths.forEachIndexed { index, path ->
                                val glyphProgress = state.glyphTraceProgress[index].value
                                if (glyphProgress > 0f) {
                                    val pathLength = pathLength[index]
                                    val distance = pathLength * glyphProgress
                                    // Draw the 'residue' stroke behind the trace stroke
                                    val residueStroke = Stroke(
                                        width = traceWidth,
                                        pathEffect = dashPathEffect(
                                            intervals = floatArrayOf(distance, pathLength),
                                            phase = 0f
                                        )
                                    )
                                    drawPath(
                                        path,
                                        color = traceResidueColor,
                                        style = residueStroke
                                    )
                                    // Draw the trace stroke
                                    val traceStroke = Stroke(
                                        width = traceWidth,
                                        pathEffect = dashPathEffect(
                                            intervals = floatArrayOf(
                                                0f,
                                                distance,
                                                traceMarkerLength,
                                                pathLength
                                            ),
                                            phase = 0f
                                        )
                                    )
                                    drawPath(path, color = traceColor, style = traceStroke)
                                }
                            }
                            if (state.fillProgress.value > 0f) {
                                if (!isFillStarted) {
                                    onFillStarted()
                                    isFillStarted = true
                                }
                                paths.forEach { path ->
                                    drawPath(
                                        path,
                                        color = Color.White,
                                        alpha = state.fillProgress.value
                                    )
                                }
                            }
                        }

                        Completed -> {
                            paths.forEach { path ->
                                drawPath(path, color = Color.White)
                            }
                        }
                    }
                }
            })
}

@Preview
@Composable
fun MuzeiLogoNotStartedPreview() {
    MuzeiLogo(
        state = NotStarted,
        modifier = Modifier.size(width = 225.dp, height = 75.dp)
    )
}

@Preview
@Composable
fun MuzeiLogoTraceInProgressPreview() {
    val traceProgress = remember {
        (0..<LogoPaths.GLYPHS.size).map { index ->
            val offset = (LogoPaths.GLYPHS.size - index).toFloat() / LogoPaths.GLYPHS.size
            mutableFloatStateOf(offset * 0.5f)
        }
    }
    val fillProgress = remember { mutableFloatStateOf(0f) }
    MuzeiLogo(
        state = InProgress(glyphTraceProgress = traceProgress, fillProgress = fillProgress),
        modifier = Modifier.size(width = 225.dp, height = 75.dp)
    )
}

@Preview
@Composable
fun MuzeiLogoFillInProgressPreview() {
    val traceProgress = remember {
        (0..<LogoPaths.GLYPHS.size).map { index ->
            mutableFloatStateOf(1f)
        }
    }
    val fillProgress = remember { mutableFloatStateOf(0.5f) }
    MuzeiLogo(
        state = InProgress(glyphTraceProgress = traceProgress, fillProgress = fillProgress),
        modifier = Modifier.size(width = 225.dp, height = 75.dp)
    )
}

@Preview
@Composable
fun MuzeiLogoCompletedPreview() {
    MuzeiLogo(
        state = Completed,
        modifier = Modifier.size(width = 225.dp, height = 75.dp)
    )
}

@Preview
@Composable
fun AnimatedMuzeiLogoPreview() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var started by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            // Animate it in when the preview starts
            started = true
        }
        Button(
            onClick = { started = !started },
        ) {
            Text(text = if (started) "Hide" else "Show")
        }
        AnimatedMuzeiLogo(
            modifier = Modifier.size(width = 225.dp, height = 125.dp),
            started = started,
        )
    }
}