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

private class CombinedPaddingValues(
    val first: PaddingValues,
    val second: PaddingValues,
    val combination: (first: Dp, second: Dp) -> Dp
) : PaddingValues {
    override fun calculateBottomPadding(): Dp =
        combination(first.calculateBottomPadding(), second.calculateBottomPadding())

    override fun calculateLeftPadding(layoutDirection: LayoutDirection): Dp =
        combination(
            first.calculateLeftPadding(layoutDirection),
            second.calculateLeftPadding(layoutDirection)
        )

    override fun calculateRightPadding(layoutDirection: LayoutDirection): Dp =
        combination(
            first.calculateRightPadding(layoutDirection),
            second.calculateRightPadding(layoutDirection)
        )

    override fun calculateTopPadding(): Dp =
        combination(first.calculateTopPadding(), second.calculateTopPadding())
}

operator fun PaddingValues.plus(other: PaddingValues): PaddingValues {
    return CombinedPaddingValues(this, other) { first, second -> first + second }
}

operator fun PaddingValues.minus(other: PaddingValues): PaddingValues {
    return CombinedPaddingValues(this, other) { first, second -> first - second }
}