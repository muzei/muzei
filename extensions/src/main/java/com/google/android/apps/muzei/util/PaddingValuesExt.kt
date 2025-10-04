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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

private class CombinedPaddingValues(
    val first: PaddingValues,
    val second: PaddingValues = PaddingValues.Zero,
    val combinationBottom: (first: Dp, second: Dp) -> Dp,
    val combinationLeft: (first: Dp, second: Dp) -> Dp,
    val combinationRight: (first: Dp, second: Dp) -> Dp,
    val combinationTop: (first: Dp, second: Dp) -> Dp,
) : PaddingValues {

    constructor(
        first: PaddingValues,
        second: PaddingValues = PaddingValues.Zero,
        combination: (first: Dp, second: Dp) -> Dp
    ) : this(first, second, combination, combination, combination, combination)

    override fun calculateBottomPadding(): Dp =
        combinationBottom(first.calculateBottomPadding(), second.calculateBottomPadding())

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        combinationLeft(
            first.calculateLeftPadding(layoutDirection),
            second.calculateLeftPadding(layoutDirection)
        )

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        combinationRight(
            first.calculateRightPadding(layoutDirection),
            second.calculateRightPadding(layoutDirection)
        )

    override fun calculateTopPadding(): Dp =
        combinationTop(first.calculateTopPadding(), second.calculateTopPadding())
}

fun PaddingValues.only(
    bottom: Boolean = false,
    left: Boolean = false,
    right: Boolean = false,
    top: Boolean = false,
): PaddingValues {
    return CombinedPaddingValues(
        this,
        combinationBottom = { first, second -> if (bottom) first else 0.dp },
        combinationLeft = { first, second -> if (left) first else 0.dp },
        combinationRight = { first, second -> if (right) first else 0.dp },
        combinationTop = { first, second -> if (top) first else 0.dp },
    )
}

operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    return CombinedPaddingValues(this, other) { first, second -> first + second }
}

operator fun PaddingValues.minus(other: PaddingValues): PaddingValues {
    return CombinedPaddingValues(this, other) { first, second -> first - second }
}