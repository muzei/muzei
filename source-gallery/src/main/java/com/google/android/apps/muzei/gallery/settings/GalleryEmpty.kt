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

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.apps.muzei.gallery.R
import com.google.android.apps.muzei.gallery.RequestStoragePermissions
import com.google.android.apps.muzei.gallery.theme.GalleryTheme
import com.google.android.apps.muzei.util.only
import com.google.android.apps.muzei.util.plus
import kotlinx.coroutines.flow.StateFlow

sealed class RequestPermissionState
data object PermissionGranted : RequestPermissionState()
data object PartialPermissionGranted : RequestPermissionState()
data class PermissionDenied(
    val shouldShowRationale: Boolean
) : RequestPermissionState()

fun checkRequestPermissionState(
    activity: Activity
): RequestPermissionState {
    return if (RequestStoragePermissions.isPartialGrant(activity)) {
        PartialPermissionGranted
    } else if (RequestStoragePermissions.checkSelfPermission(activity)) {
        PermissionGranted
    } else {
        PermissionDenied(
            shouldShowRationale = RequestStoragePermissions.shouldShowRequestPermissionRationale(
                activity
            )
        )
    }
}

@Composable
fun GalleryEmpty(
    permissionStateFlow: StateFlow<RequestPermissionState>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onReselectSelectedPhotos: () -> Unit = {},
    onEnableRandom: () -> Unit = {},
    onEditPermissionSettings: () -> Unit = {},
) {
    val permissionState by permissionStateFlow.collectAsState()
    GalleryEmpty(
        permissionState = permissionState,
        modifier = modifier,
        contentPadding = contentPadding,
        onReselectSelectedPhotos = onReselectSelectedPhotos,
        onEnableRandom = onEnableRandom,
        onEditPermissionSettings = onEditPermissionSettings,
    )
}

@Composable
fun GalleryEmpty(
    permissionState: RequestPermissionState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onReselectSelectedPhotos: () -> Unit = {},
    onEnableRandom: () -> Unit = {},
    onEditPermissionSettings: () -> Unit = {},
) {
    Box(modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(
                    PaddingValues(32.dp) + contentPadding.only(
                        left = true,
                        right = true,
                        top = true
                    )
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(permissionState) { state ->
                when (state) {
                    PartialPermissionGranted -> {
                        Button(
                            onClick = onReselectSelectedPhotos
                        ) {
                            Text(text = stringResource(R.string.gallery_reselect_selected_photos))
                        }
                    }

                    is PermissionGranted -> {
                        GalleryEmptyStateGraphic()
                    }

                    is PermissionDenied -> {
                        if (state.shouldShowRationale) {
                            Button(
                                onClick = onEnableRandom
                            ) {
                                Text(text = stringResource(R.string.gallery_enable_random))
                            }
                        } else {
                            Button(
                                onClick = onEditPermissionSettings
                            ) {
                                Text(text = stringResource(R.string.gallery_edit_settings))
                            }
                        }
                    }
                }
            }
            val emptyDescription = when (permissionState) {
                PartialPermissionGranted -> R.string.gallery_empty
                is PermissionGranted -> R.string.gallery_empty
                is PermissionDenied -> if (permissionState.shouldShowRationale) {
                    R.string.gallery_permission_rationale
                } else {
                    R.string.gallery_denied_explanation
                }
            }
            Text(
                text = stringResource(emptyDescription),
                modifier = Modifier.padding(top = 16.dp, bottom = 64.dp),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 22.sp,
                fontStyle = FontStyle.Italic,
                fontFamily = FontFamily.SansSerif,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = stringResource(R.string.gallery_empty_subtitle),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(contentPadding.only(bottom = true, left = true, right = true)),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 16.sp,
            fontStyle = FontStyle.Italic,
            fontFamily = FontFamily.SansSerif,
        )
    }
}

@Preview
@Composable
fun GalleryEmptyGrantedPreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        GalleryEmpty(
            permissionState = PermissionGranted,
            modifier = Modifier.size(height = 800.dp, width = 400.dp),
        )
    }
}

@Preview
@Composable
fun GalleryEmptyPartialGrantedGrantedPreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        GalleryEmpty(
            permissionState = PartialPermissionGranted,
            modifier = Modifier.size(height = 800.dp, width = 400.dp),
        )
    }
}

@Preview
@Composable
fun GalleryEmptyDeniedPreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        GalleryEmpty(
            permissionState = PermissionDenied(shouldShowRationale = false),
            modifier = Modifier.size(height = 800.dp, width = 400.dp),
        )
    }
}

@Preview
@Composable
fun GalleryEmptyDeniedRationalePreview() {
    GalleryTheme(
        dynamicColor = false,
    ) {
        GalleryEmpty(
            permissionState = PermissionDenied(shouldShowRationale = true),
            modifier = Modifier.size(height = 800.dp, width = 400.dp),
        )
    }
}