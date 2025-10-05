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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.gallery.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryImportPhotosDialog(
    getContentActivityInfoList: List<String>,
    onInfoSelected: (Int) -> Unit,
    onDismissRequest: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        GalleryImportPhotosDialogContent(
            getContentActivityInfoList,
            onInfoSelected,
        )
    }
}

@Composable
private fun GalleryImportPhotosDialogContent(
    getContentActivityInfoList: List<String>,
    onInfoSelected: (Int) -> Unit = {},
) {
    Surface(
        modifier = Modifier
            .wrapContentWidth()
            .wrapContentHeight(),
        shape = AlertDialogDefaults.shape,
        color = AlertDialogDefaults.containerColor,
        tonalElevation = AlertDialogDefaults.TonalElevation,
    ) {
        Column(
            modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.gallery_import_dialog_title),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                color = AlertDialogDefaults.titleContentColor,
                style = MaterialTheme.typography.headlineSmall,
            )
            LazyColumn(
                modifier = Modifier,
                contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
                //verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(getContentActivityInfoList) { index, info ->
                    TextButton(
                        onClick = { onInfoSelected(index) },
                    ) {
                        Text(
                            text = getContentActivityInfoList[index],
                            modifier = Modifier.fillMaxWidth(),
                            color = AlertDialogDefaults.textContentColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun GalleryImportPhotosDialogContentPreview() {
    GalleryImportPhotosDialogContent(
        listOf("Google Photos", "Gallery", "Photo Picker")
    )
}