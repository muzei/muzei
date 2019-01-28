package com.google.android.apps.muzei

import android.support.wearable.complications.rendering.ComplicationDrawable
import android.util.AndroidRuntimeException

/**
 * It's like [ComplicationDrawable.onTap], but doesn't throw an [AndroidRuntimeException].
 */
fun ComplicationDrawable.safeOnTap(x: Int, y: Int) =  try {
    onTap(x, y)
} catch (e: AndroidRuntimeException) {
    // Catch errors from https://issuetracker.google.com/issues/117685089
    false
}
