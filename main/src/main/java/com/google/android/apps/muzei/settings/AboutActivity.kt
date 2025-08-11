/*
 * Copyright 2014 Google Inc.
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

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.core.FloatTweenSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.compose.AndroidFragment
import com.google.android.apps.muzei.render.MuzeiRendererFragment
import com.google.android.apps.muzei.util.AnimatedMuzeiLogoFragment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R
import net.nurik.roman.muzei.androidclientcommon.R as CommonR

class AboutActivity : AppCompatActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Box {
                var visible by rememberSaveable {
                    mutableStateOf(false)
                }
                val animatedAlpha by animateFloatAsState(
                    targetValue = if (visible) 1.0f else 0f,
                    animationSpec = FloatTweenSpec(duration = 1000),
                    label = "alpha",
                )
                MuzeiRendererFragment(
                    demoMode = true,
                    demoFocus = false,
                    modifier = Modifier.graphicsLayer {
                        alpha = animatedAlpha
                    }
                )
                val scrollBehavior =
                    TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
                Scaffold(
                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        TopAppBar(
                            title = {},
                            navigationIcon = {
                                IconButton(
                                    onClick = { onNavigateUp() },
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = stringResource(R.string.navigate_up)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color.Transparent,
                                scrolledContainerColor = Color.Transparent,
                                navigationIconContentColor = Color.White,
                            ),
                            scrollBehavior = scrollBehavior,
                        )
                    },
                    containerColor = Color.Transparent,
                    contentColor = Color(0xAAFFFFFF),
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .verticalScroll(
                                rememberScrollState(),
                                overscrollEffect = null
                            )
                            .padding(paddingValues),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentWidth()
                                .widthIn(max = 600.dp)
                                .padding(horizontal = 32.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val coroutineScope = rememberCoroutineScope()
                            AndroidFragment<AnimatedMuzeiLogoFragment> { fragment ->
                                coroutineScope.launch {
                                    if (!visible) {
                                        delay(250)
                                        visible = true
                                        delay(1000)
                                        fragment.start()
                                    }
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.about_version_template,
                                    BuildConfig.VERSION_NAME
                                ),
                                fontSize = dimensionResource(R.dimen.settings_text_size_normal).value.sp,
                                fontFamily = FontFamily.SansSerif,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                            Text(
                                text = AnnotatedString.fromHtml(
                                    stringResource(R.string.about_body),
                                    linkStyles = TextLinkStyles(
                                        style = SpanStyle(
                                            color = Color.White,
                                            textDecoration = TextDecoration.Underline,
                                        )
                                    )
                                ),
                                fontSize = dimensionResource(R.dimen.settings_text_size_normal).value.sp,
                                fontFamily = FontFamily.SansSerif,
                                lineHeight = dimensionResource(R.dimen.settings_line_spacing_normal).value.sp,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                            Image(
                                painterResource(R.drawable.about_android_experiment),
                                contentDescription = stringResource(R.string.about_an_android_experiment),
                                modifier = Modifier
                                    .padding(top = 16.dp)
                                    .clickable {
                                        val cti = CustomTabsIntent.Builder()
                                            .setShowTitle(true)
                                            .setDefaultColorSchemeParams(
                                                CustomTabColorSchemeParams.Builder()
                                                    .setToolbarColor(
                                                        ContextCompat.getColor(
                                                            this@AboutActivity,
                                                            CommonR.color.theme_primary
                                                        )
                                                    )
                                                    .build()
                                            )
                                            .build()
                                        try {
                                            cti.launchUrl(
                                                this@AboutActivity,
                                                "https://www.androidexperiments.com/experiment/muzei".toUri()
                                            )
                                        } catch (_: ActivityNotFoundException) {
                                        }
                                    }
                                    .padding(horizontal = 24.dp, vertical = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}