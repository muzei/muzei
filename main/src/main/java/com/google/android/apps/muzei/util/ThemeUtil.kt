package com.google.android.apps.muzei.util

import android.content.res.Configuration
import android.content.res.Resources

fun Resources.isNightMode() =
	(configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES