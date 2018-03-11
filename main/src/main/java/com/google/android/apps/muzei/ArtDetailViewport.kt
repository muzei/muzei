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

package com.google.android.apps.muzei

import android.graphics.RectF

import org.greenrobot.eventbus.EventBus

// Singleton that also behaves as an event
object ArtDetailViewport {
    private val viewport0 = RectF()
    private val viewport1 = RectF()
    var isFromUser: Boolean = false
        private set

    fun getViewport(id: Int): RectF {
        return if (id == 0) viewport0 else viewport1
    }

    fun setViewport(id: Int, viewport: RectF, fromUser: Boolean = false) {
        setViewport(id, viewport.left, viewport.top, viewport.right, viewport.bottom,
                fromUser)
    }

    fun setViewport(id: Int, left: Float, top: Float, right: Float, bottom: Float,
                    fromUser: Boolean = false) {
        isFromUser = fromUser
        getViewport(id).set(left, top, right, bottom)
        EventBus.getDefault().post(this)
    }

    fun setDefaultViewport(id: Int, bitmapAspectRatio: Float,
                           screenAspectRatio: Float): ArtDetailViewport {
        isFromUser = false
        if (bitmapAspectRatio > screenAspectRatio) {
            getViewport(id).set(
                    0.5f - screenAspectRatio / bitmapAspectRatio / 2f,
                    0f,
                    0.5f + screenAspectRatio / bitmapAspectRatio / 2f,
                    1f)
        } else {
            getViewport(id).set(
                    0f,
                    0.5f - bitmapAspectRatio / screenAspectRatio / 2f,
                    1f,
                    0.5f + bitmapAspectRatio / screenAspectRatio / 2f)
        }
        EventBus.getDefault().post(this)
        return this
    }
}
