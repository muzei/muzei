/*
 * Copyright 2026 Google Inc.
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

package com.google.android.apps.muzei.render

import android.graphics.Bitmap
import android.opengl.GLES20
import android.util.Log
import com.google.android.apps.muzei.util.divideRoundUp
import net.nurik.roman.muzei.BuildConfig
import pl.droidsonroids.gif.GifAnimationMetaData
import pl.droidsonroids.gif.GifOptions
import pl.droidsonroids.gif.GifTexImage2D
import pl.droidsonroids.gif.InputSource
import java.io.BufferedInputStream
import kotlin.math.max

private const val GIF_HEADER_LENGTH = 6
private const val MIN_FRAME_DELAY_MILLIS = 34L

internal object GifHeaderDetector {
    private val gif87a = "GIF87a".encodeToByteArray()
    private val gif89a = "GIF89a".encodeToByteArray()

    fun isGif(header: ByteArray, byteCount: Int = header.size): Boolean {
        if (byteCount < GIF_HEADER_LENGTH) {
            return false
        }
        return header.matches(gif87a) || header.matches(gif89a)
    }

    private fun ByteArray.matches(expected: ByteArray): Boolean {
        for (index in expected.indices) {
            if (this[index] != expected[index]) {
                return false
            }
        }
        return true
    }
}

internal data class GifMetadata(
        val width: Int,
        val height: Int,
        val frameCount: Int,
        val loopCount: Int,
        val durationMillis: Int
)

internal enum class ArtworkKind {
    STATIC,
    ANIMATED
}

internal fun classifyArtwork(hasGifHeader: Boolean, metadata: GifMetadata?): ArtworkKind =
        if (hasGifHeader && metadata != null && metadata.frameCount > 1 &&
                metadata.durationMillis > 0) {
            ArtworkKind.ANIMATED
        } else {
            ArtworkKind.STATIC
        }

internal fun sanitizeGifFrameDelay(frameDelayMillis: Long): Long =
        max(MIN_FRAME_DELAY_MILLIS, frameDelayMillis)

internal fun calculateGifSampleSize(
        metadata: GifMetadata,
        targetWidth: Int,
        targetHeight: Int,
        maxTextureSize: Int
): Int {
    val widthSample = if (targetWidth > 0) metadata.width / targetWidth else 1
    val heightSample = if (targetHeight > 0) metadata.height / targetHeight else 1
    val textureSample = if (maxTextureSize > 0) {
        max(
                metadata.width.divideRoundUp(maxTextureSize),
                metadata.height.divideRoundUp(maxTextureSize))
    } else {
        1
    }
    return max(1, max(textureSample, max(widthSample, heightSample)))
}

internal interface AnimatedArtworkDecoder : AutoCloseable {
    val width: Int
    val height: Int
    val frameCount: Int
    val loopCount: Int

    fun frameDurationMillis(frameIndex: Int): Long
    fun seekToFrame(frameIndex: Int)
    fun uploadCurrentFrame()
    fun updateTexture()
}

internal sealed interface DecodedArtwork {
    val firstFrame: Bitmap

    data class Static(override val firstFrame: Bitmap) : DecodedArtwork

    data class Animated(
            override val firstFrame: Bitmap,
            val decoder: AnimatedArtworkDecoder
    ) : DecodedArtwork
}

private class GifAnimatedArtworkDecoder(
        private val gif: GifTexImage2D,
        override val loopCount: Int
) : AnimatedArtworkDecoder {
    override val width: Int = gif.width
    override val height: Int = gif.height
    override val frameCount: Int = gif.numberOfFrames
    private var closed = false

    override fun frameDurationMillis(frameIndex: Int): Long =
            gif.getFrameDuration(frameIndex).toLong()

    override fun seekToFrame(frameIndex: Int) {
        gif.seekToFrame(frameIndex)
    }

    override fun uploadCurrentFrame() {
        gif.glTexImage2D(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun updateTexture() {
        gif.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun close() {
        if (!closed) {
            closed = true
            gif.recycle()
        }
    }
}

internal fun ImageLoader.decodeArtwork(
        targetWidth: Int,
        targetHeight: Int,
        maxTextureSize: Int
): DecodedArtwork? {
    val firstFrame = decode(targetWidth, targetHeight) ?: return null
    if (!hasGifHeader()) {
        return DecodedArtwork.Static(firstFrame)
    }

    val metadata = readGifMetadata()
    if (classifyArtwork(true, metadata) != ArtworkKind.ANIMATED || metadata == null) {
        return DecodedArtwork.Static(firstFrame)
    }

    val decoder = try {
        openGifDecoder(metadata, targetWidth, targetHeight, maxTextureSize)
    } catch (exception: Exception) {
        if (BuildConfig.DEBUG) {
            Log.w("AnimatedArtwork", "Animated GIF decoding failed; using its first frame", exception)
        }
        null
    }
    return if (decoder != null) {
        DecodedArtwork.Animated(firstFrame, decoder)
    } else {
        DecodedArtwork.Static(firstFrame)
    }
}

private fun ImageLoader.hasGifHeader(): Boolean = try {
    openInputStream()?.use { input ->
        val header = ByteArray(GIF_HEADER_LENGTH)
        var byteCount = 0
        while (byteCount < header.size) {
            val read = input.read(header, byteCount, header.size - byteCount)
            if (read < 0) {
                break
            }
            byteCount += read
        }
        GifHeaderDetector.isGif(header, byteCount)
    } ?: false
} catch (_: Exception) {
    false
}

private fun ImageLoader.readGifMetadata(): GifMetadata? {
    val input = openInputStream()?.let(::BufferedInputStream) ?: return null
    return try {
        val metadata = GifAnimationMetaData(input)
        GifMetadata(
                metadata.width,
                metadata.height,
                metadata.numberOfFrames,
                metadata.loopCount,
                metadata.duration)
    } catch (exception: Exception) {
        input.close()
        if (BuildConfig.DEBUG) {
            Log.w("AnimatedArtwork", "GIF metadata could not be read", exception)
        }
        null
    }
}

private fun ImageLoader.openGifDecoder(
        metadata: GifMetadata,
        targetWidth: Int,
        targetHeight: Int,
        maxTextureSize: Int
): AnimatedArtworkDecoder? {
    val input = openInputStream()?.let(::BufferedInputStream) ?: return null
    return try {
        val options = GifOptions().apply {
            setInSampleSize(calculateGifSampleSize(
                    metadata, targetWidth, targetHeight, maxTextureSize))
        }
        val gif = GifTexImage2D(InputSource.InputStreamSource(input), options)
        if (gif.width <= 0 || gif.height <= 0 || gif.numberOfFrames <= 1) {
            gif.recycle()
            null
        } else {
            GifAnimatedArtworkDecoder(gif, metadata.loopCount)
        }
    } catch (exception: Exception) {
        input.close()
        throw exception
    }
}

internal class AnimatedArtworkPlayback(
        val decoder: AnimatedArtworkDecoder
) : AutoCloseable {
    var currentFrameIndex = 0
        private set
    private var completedLoops = 0
    private var nextFrameUptimeMillis = Long.MAX_VALUE
    private var running = false
    private var finished = false
    private var closed = false

    init {
        require(decoder.frameCount > 1)
    }

    fun setVisible(visible: Boolean, nowUptimeMillis: Long) {
        if (closed || finished || running == visible) {
            return
        }
        running = visible
        nextFrameUptimeMillis = if (visible) {
            nowUptimeMillis + sanitizeGifFrameDelay(
                    decoder.frameDurationMillis(currentFrameIndex))
        } else {
            Long.MAX_VALUE
        }
    }

    fun advance(nowUptimeMillis: Long): Int? {
        if (!running || nowUptimeMillis < nextFrameUptimeMillis) {
            return null
        }

        var frameToRender: Int? = null
        var framesAdvanced = 0
        while (running && nowUptimeMillis >= nextFrameUptimeMillis) {
            val nextFrame = currentFrameIndex + 1
            if (nextFrame == decoder.frameCount) {
                completedLoops++
                if (decoder.loopCount > 0 && completedLoops >= decoder.loopCount) {
                    running = false
                    finished = true
                    nextFrameUptimeMillis = Long.MAX_VALUE
                    break
                }
                currentFrameIndex = 0
            } else {
                currentFrameIndex = nextFrame
            }
            frameToRender = currentFrameIndex
            nextFrameUptimeMillis += sanitizeGifFrameDelay(
                    decoder.frameDurationMillis(currentFrameIndex))

            // Avoid spending an unbounded amount of time catching up after a stalled render.
            framesAdvanced++
            if (framesAdvanced >= decoder.frameCount * 2) {
                nextFrameUptimeMillis = nowUptimeMillis + sanitizeGifFrameDelay(
                        decoder.frameDurationMillis(currentFrameIndex))
                break
            }
        }
        return frameToRender
    }

    fun millisUntilNextFrame(nowUptimeMillis: Long): Long? =
            if (running) max(0, nextFrameUptimeMillis - nowUptimeMillis) else null

    override fun close() {
        if (!closed) {
            closed = true
            running = false
            nextFrameUptimeMillis = Long.MAX_VALUE
            decoder.close()
        }
    }
}

internal class AnimatedArtworkSlot : AutoCloseable {
    var playback: AnimatedArtworkPlayback? = null
        private set

    fun replace(next: AnimatedArtworkPlayback?) {
        if (playback === next) {
            return
        }
        playback?.close()
        playback = next
    }

    override fun close() {
        replace(null)
    }
}
