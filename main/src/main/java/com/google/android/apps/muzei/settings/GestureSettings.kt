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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Devices.TABLET
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.only
import net.nurik.roman.muzei.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureSettings(
    doubleTapSelectedOption: String,
    onDoubleTapSelectedOptionChange: (String) -> Unit,
    threeFingerSelectedOption: String,
    onThreeFingerSelectedOptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onUp: () -> Unit = {},
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.gestures_title))
                },
                navigationIcon = {
                    IconButton(
                        onClick = onUp,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_up)
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentWidth()
                .padding(innerPadding.only(left = true, right = true))
                .widthIn(max = 500.dp)
                .padding(innerPadding.only(bottom = true, top = true))
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(
                title = stringResource(R.string.gestures_double_tap_title),
                description = stringResource(R.string.gestures_double_tap_description),
            )
            val gestureOptions = listOf(
                stringResource(R.string.gestures_tap_action_temporary_disable),
                stringResource(R.string.gestures_tap_action_next),
                stringResource(R.string.gestures_tap_action_view_details),
                stringResource(R.string.gestures_tap_action_none),
            )
            RadioButtonGroup(
                options = gestureOptions,
                selectedOption = doubleTapSelectedOption,
                onOptionSelected = onDoubleTapSelectedOptionChange,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            SectionHeader(
                title = stringResource(R.string.gestures_three_finger_tap_title),
                description = stringResource(R.string.gestures_three_finger_tap_description)
            )
            RadioButtonGroup(
                options = gestureOptions,
                selectedOption = threeFingerSelectedOption,
                onOptionSelected = onThreeFingerSelectedOptionChange,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    description: String,
) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.headlineSmall,
    )
    Text(
        text = description,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun RadioButtonGroup(
    options: List<String>,
    selectedOption: String,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.selectableGroup()) {
        options.forEach { text ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .selectable(
                        selected = (text == selectedOption),
                        onClick = { onOptionSelected(text) },
                        role = Role.RadioButton
                    ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = (text == selectedOption),
                    onClick = null,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp, end = 16.dp)
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
fun GestureSettingsPreview() {
    AppTheme(
        dynamicColor = false
    ) {
        val defaultDoubleTapOption = stringResource(R.string.gestures_tap_action_temporary_disable)
        var doubleTapSelectedOption by remember { mutableStateOf(defaultDoubleTapOption) }
        val defaultThreeFingerOption = stringResource(R.string.gestures_tap_action_none)
        var threeFingerSelectedOption by remember { mutableStateOf(defaultThreeFingerOption) }
        GestureSettings(
            doubleTapSelectedOption = doubleTapSelectedOption,
            onDoubleTapSelectedOptionChange = { doubleTapSelectedOption = it },
            threeFingerSelectedOption = threeFingerSelectedOption,
            onThreeFingerSelectedOptionChange = { threeFingerSelectedOption = it },
            modifier = Modifier.fillMaxSize(),
        )
    }
}