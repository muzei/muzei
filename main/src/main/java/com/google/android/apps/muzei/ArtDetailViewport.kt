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
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

// Singleton that can be observed
object ArtDetailViewport {
    private val viewport0 = RectF()
    private val viewport1 = RectF()
    private val observers = mutableListOf<(isFromUser: Boolean) -> Unit>()
    private val changeLiveData = MutableLiveData<Boolean>().apply {
        @Suppress("EXPERIMENTAL_API_USAGE")
        GlobalScope.launch(Dispatchers.Main.immediate) {
            // Make sure we trigger observers on the main thread
            observeForever { isFromUser ->
                observers.forEach { it.invoke(isFromUser == true) }
            }
        }
    }

    fun addObserver(observer: (isFromUser: Boolean) -> Unit) {
        observers.add(observer)
    }

    fun removeObserver(observer: (isFromUser: Boolean) -> Unit) {
        observers.remove(observer)
    }

    fun getViewport(id: Int): RectF {
        return if (id == 0) viewport0 else viewport1
    }

    fun setViewport(id: Int, viewport: RectF, fromUser: Boolean = false) {
        setViewport(id, viewport.left, viewport.top, viewport.right, viewport.bottom,
                fromUser)
    }

    fun setViewport(
            id: Int,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            isFromUser: Boolean = false
    ) {
        getViewport(id).set(left, top, right, bottom)
        changeLiveData.postValue(isFromUser)
    }

    fun setDefaultViewport(
            id: Int,
            bitmapAspectRatio: Float,
            screenAspectRatio: Float
    ): ArtDetailViewport {
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
        changeLiveData.postValue(false)
        return this
    }
}
