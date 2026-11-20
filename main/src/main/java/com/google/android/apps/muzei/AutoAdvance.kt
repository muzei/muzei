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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Devices.PHONE
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.theme.AppTheme
import com.google.android.apps.muzei.util.RadioButtonGroup
import com.google.android.apps.muzei.util.RadioButtonSectionHeader
import com.google.android.apps.muzei.util.toast
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import net.nurik.roman.muzei.R

private const val TASKER_PACKAGE_NAME = "net.dinglisch.android.taskerm"

@Composable
fun AutoAdvance(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val providerManager = remember { ProviderManager.getInstance(context) }
    AutoAdvance(
        providerManager = providerManager,
        modifier = modifier,
        contentPadding = contentPadding,
        onOpenTasker = {
            try {
                val pm = context.packageManager
                context.startActivity(
                    (pm.getLaunchIntentForPackage(TASKER_PACKAGE_NAME)
                        ?: Intent(
                            Intent.ACTION_VIEW,
                            ("https://play.google.com/store/apps/details?id=" +
                                    TASKER_PACKAGE_NAME +
                                    "&referrer=utm_source%3Dmuzei%26utm_medium%3Dapp%26utm_campaign%3Dauto_advance").toUri()
                        )
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                )
                Firebase.analytics.logEvent("tasker_open", null)
            } catch (_: ActivityNotFoundException) {
                context.toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
            } catch (_: SecurityException) {
                context.toast(R.string.play_store_not_found, Toast.LENGTH_LONG)
            }
        }
    )
}

@Composable
fun AutoAdvance(
    providerManager: ProviderManager,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenTasker: () -> Unit = {},
) {
    // Advance on Wifi
    val defaultAdvanceOnWifi = remember {
        providerManager.loadOnWifi
    }
    var advanceOnWifi by remember { mutableStateOf(defaultAdvanceOnWifi) }

    // Ordering
    val orderingOptions = listOf(
        stringResource(R.string.auto_advance_ordering_in_order),
        stringResource(R.string.auto_advance_ordering_new_in_order),
        stringResource(R.string.auto_advance_ordering_random),
    )
    val orderingToStringMapper: (ordering: ProviderManager.LoadOrdering) -> String = { ordering ->
        when (ordering) {
            ProviderManager.LoadOrdering.IN_ORDER -> orderingOptions[0]
            ProviderManager.LoadOrdering.NEW_IN_ORDER -> orderingOptions[1]
            ProviderManager.LoadOrdering.RANDOM -> orderingOptions[2]
        }
    }
    val stringToOrderingMapper: (string: String) -> ProviderManager.LoadOrdering = { string ->
        when (string) {
            orderingOptions[0] -> ProviderManager.LoadOrdering.IN_ORDER
            orderingOptions[1] -> ProviderManager.LoadOrdering.NEW_IN_ORDER
            else -> ProviderManager.LoadOrdering.RANDOM
        }
    }
    val defaultOrderingOption = remember {
        val orderingValue = providerManager.loadOrdering
        orderingToStringMapper(orderingValue)
    }
    var orderingSelectedOption by remember { mutableStateOf(defaultOrderingOption) }

    // Interval
    val intervalOptions = listOf(
        stringResource(R.string.auto_advance_interval_15m),
        stringResource(R.string.auto_advance_interval_30m),
        stringResource(R.string.auto_advance_interval_1h),
        stringResource(R.string.auto_advance_interval_3h),
        stringResource(R.string.auto_advance_interval_6h),
        stringResource(R.string.auto_advance_interval_24h),
        stringResource(R.string.auto_advance_interval_72h),
        stringResource(R.string.auto_advance_interval_never),
    )
    val intervalToStringMapper: (interval: Long) -> String = { interval ->
        when (interval) {
            60L * 15 -> intervalOptions[0]
            60L * 30 -> intervalOptions[1]
            60L * 60 -> intervalOptions[2]
            60L * 60 * 3 -> intervalOptions[3]
            60L * 60 * 6 -> intervalOptions[4]
            60L * 60 * 24 -> intervalOptions[5]
            60L * 60 * 72 -> intervalOptions[6]
            else -> intervalOptions[7]
        }
    }
    val stringToIntervalMapper: (string: String) -> Long = { string ->
        when (string) {
            intervalOptions[0] -> 60L * 15
            intervalOptions[1] -> 60L * 30
            intervalOptions[2] -> 60L * 60
            intervalOptions[3] -> 60L * 60 * 3
            intervalOptions[4] -> 60L * 60 * 6
            intervalOptions[5] -> 60L * 60 * 24
            intervalOptions[6] -> 60L * 60 * 72
            else -> 0
        }
    }
    val defaultIntervalOption = remember {
        val intervalValue = providerManager.loadFrequencySeconds
        intervalToStringMapper(intervalValue)
    }
    var intervalSelectedOption by remember { mutableStateOf(defaultIntervalOption) }

    AutoAdvance(
        advanceOnWifi = advanceOnWifi,
        onAdvanceOnWifiChanged = { isChecked ->
            Firebase.analytics.logEvent("auto_advance_load_on_wifi") {
                param(FirebaseAnalytics.Param.VALUE, isChecked.toString())
            }
            providerManager.loadOnWifi = isChecked
            advanceOnWifi = isChecked
        },
        orderingSelectedOption = orderingSelectedOption,
        onOrderingSelectedOptionChange = { selected ->
            Firebase.analytics.logEvent("auto_advance_load_ordering") {
                param(FirebaseAnalytics.Param.VALUE, selected)
            }
            providerManager.loadOrdering = stringToOrderingMapper(selected)
            orderingSelectedOption = selected
        },
        intervalSelectedOption = intervalSelectedOption,
        onIntervalSelectedOptionChange = { selected ->
            val newInterval = stringToIntervalMapper(selected)
            Firebase.analytics.logEvent("auto_advance_load_frequency") {
                param(FirebaseAnalytics.Param.VALUE, newInterval)
            }
            providerManager.loadFrequencySeconds = newInterval
            intervalSelectedOption = selected
        },
        modifier = modifier,
        contentPadding = contentPadding,
        onOpenTasker = onOpenTasker,
    )
}

@Composable
fun AutoAdvance(
    advanceOnWifi: Boolean,
    onAdvanceOnWifiChanged: (Boolean) -> Unit,
    orderingSelectedOption: String,
    onOrderingSelectedOptionChange: (String) -> Unit,
    intervalSelectedOption: String,
    onIntervalSelectedOptionChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    onOpenTasker: () -> Unit = {},
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(contentPadding),
    ) {
        Text(
            text = stringResource(R.string.auto_advance_title),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
            style = MaterialTheme.typography.headlineLarge,
        )

        Text(
            text = stringResource(R.string.auto_advance_description),
            modifier = Modifier.padding(start = 16.dp, end = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .selectable(
                    selected = advanceOnWifi,
                    onClick = { onAdvanceOnWifiChanged(!advanceOnWifi) },
                    role = Role.RadioButton
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = advanceOnWifi,
                onCheckedChange = null,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
            )
            Text(
                text = stringResource(R.string.auto_advance_wifi),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, end = 16.dp)
            )
        }
        RadioButtonSectionHeader(
            title = stringResource(R.string.auto_advance_ordering),
        )
        val orderingOptions = listOf(
            stringResource(R.string.auto_advance_ordering_in_order),
            stringResource(R.string.auto_advance_ordering_new_in_order),
            stringResource(R.string.auto_advance_ordering_random),
        )
        RadioButtonGroup(
            options = orderingOptions,
            selectedOption = orderingSelectedOption,
            onOptionSelected = onOrderingSelectedOptionChange,
        )
        RadioButtonSectionHeader(
            title = stringResource(R.string.auto_advance_interval),
        )
        val intervalOptions = listOf(
            stringResource(R.string.auto_advance_interval_15m),
            stringResource(R.string.auto_advance_interval_30m),
            stringResource(R.string.auto_advance_interval_1h),
            stringResource(R.string.auto_advance_interval_3h),
            stringResource(R.string.auto_advance_interval_6h),
            stringResource(R.string.auto_advance_interval_24h),
            stringResource(R.string.auto_advance_interval_72h),
            stringResource(R.string.auto_advance_interval_never),
        )
        RadioButtonGroup(
            options = intervalOptions,
            selectedOption = intervalSelectedOption,
            onOptionSelected = onIntervalSelectedOptionChange,
        )
        val taskerDescription = stringResource(R.string.auto_advance_tasker)
        val taskerName = stringResource(R.string.auto_advance_tasker_name)
        val taskerText = remember(taskerDescription, taskerName) {
            buildAnnotatedString {
                append(taskerDescription.substringBefore(taskerName))
                withStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.Underline,
                        fontStyle = FontStyle.Italic,
                    )
                ) {
                    append(taskerName)
                }
                append(taskerDescription.substringAfter(taskerName))
            }
        }
        Text(
            text = taskerText,
            modifier = Modifier
                .clickable(onClick = onOpenTasker)
                .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
        )
    }
}

@Preview(name = "Portrait", device = PHONE)
@Composable
fun AutoAdvancePreview() {
    AppTheme(
        dynamicColor = false
    ) {
        val defaultAdvanceOnWifi = false
        var advanceOnWifi by remember { mutableStateOf(defaultAdvanceOnWifi) }
        val defaultOrderingOption = stringResource(R.string.auto_advance_ordering_new_in_order)
        var orderingOption by remember { mutableStateOf(defaultOrderingOption) }
        val defaultIntervalOption = stringResource(R.string.auto_advance_interval_1h)
        var intervalSelectedOption by remember { mutableStateOf(defaultIntervalOption) }

        AutoAdvance(
            advanceOnWifi = advanceOnWifi,
            onAdvanceOnWifiChanged = { advanceOnWifi = it },
            orderingSelectedOption = orderingOption,
            onOrderingSelectedOptionChange = { orderingOption = it },
            intervalSelectedOption = intervalSelectedOption,
            onIntervalSelectedOptionChange = { intervalSelectedOption = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}