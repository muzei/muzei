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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedArtworkTest {

    @Test
    fun gifHeaderDetectionUsesContent() {
        assertTrue(GifHeaderDetector.isGif("GIF87a-more".encodeToByteArray()))
        assertTrue(GifHeaderDetector.isGif("GIF89a-more".encodeToByteArray()))
        assertFalse(GifHeaderDetector.isGif("\u0089PNG\r\n".encodeToByteArray()))
        assertFalse(GifHeaderDetector.isGif("GIF".encodeToByteArray()))
    }

    @Test
    fun staticImagesAndSingleFrameGifsAreStatic() {
        val animatedMetadata = GifMetadata(20, 20, 2, 0, 200)
        val singleFrameMetadata = GifMetadata(20, 20, 1, 1, 100)

        assertEquals(ArtworkKind.STATIC, classifyArtwork(false, animatedMetadata))
        assertEquals(ArtworkKind.STATIC, classifyArtwork(true, singleFrameMetadata))
        assertEquals(ArtworkKind.ANIMATED, classifyArtwork(true, animatedMetadata))
    }

    @Test
    fun decoderFailureFallsBackToStaticClassification() {
        assertEquals(ArtworkKind.STATIC, classifyArtwork(true, null))
        assertEquals(
                ArtworkKind.STATIC,
                classifyArtwork(true, GifMetadata(20, 20, 2, 0, 0)))
    }

    @Test
    fun frameDelayIsCappedAtThirtyFramesPerSecond() {
        assertEquals(34L, sanitizeGifFrameDelay(0))
        assertEquals(34L, sanitizeGifFrameDelay(10))
        assertEquals(50L, sanitizeGifFrameDelay(50))
    }

    @Test
    fun largeGifIsSampledTowardTargetAndTextureLimits() {
        val metadata = GifMetadata(8000, 4000, 2, 0, 200)

        assertEquals(4, calculateGifSampleSize(metadata, 2000, 1000, 4096))
        assertEquals(8, calculateGifSampleSize(metadata, 1000, 500, 4096))
    }

    @Test
    fun playbackSchedulesFramesFromTheirDeadlines() {
        val playback = AnimatedArtworkPlayback(FakeDecoder(
                frameDurations = longArrayOf(40, 50),
                loopCount = 1))

        playback.setVisible(true, 100)
        assertEquals(40L, playback.millisUntilNextFrame(100))
        assertNull(playback.advance(139))
        assertEquals(1, playback.advance(140))
        assertEquals(50L, playback.millisUntilNextFrame(140))
        assertNull(playback.advance(190))
        assertNull(playback.millisUntilNextFrame(190))
    }

    @Test
    fun playbackHonorsFiniteLoopCount() {
        val playback = AnimatedArtworkPlayback(FakeDecoder(
                frameDurations = longArrayOf(34, 34),
                loopCount = 2))

        playback.setVisible(true, 0)
        assertEquals(1, playback.advance(34))
        assertEquals(0, playback.advance(68))
        assertEquals(1, playback.advance(102))
        assertNull(playback.advance(136))
        assertNull(playback.millisUntilNextFrame(136))
        playback.setVisible(true, 200)
        assertNull(playback.millisUntilNextFrame(200))
    }

    @Test
    fun playbackPausesAndRestartsDeadlineWhenVisibleAgain() {
        val playback = AnimatedArtworkPlayback(FakeDecoder(longArrayOf(10, 10)))

        playback.setVisible(true, 0)
        playback.setVisible(false, 10)
        assertNull(playback.millisUntilNextFrame(10))
        playback.setVisible(true, 100)
        assertEquals(34L, playback.millisUntilNextFrame(100))
    }

    @Test
    fun replacingArtworkClosesOldDecoder() {
        val firstDecoder = FakeDecoder(longArrayOf(40, 40))
        val secondDecoder = FakeDecoder(longArrayOf(40, 40))
        val slot = AnimatedArtworkSlot()

        slot.replace(AnimatedArtworkPlayback(firstDecoder))
        slot.replace(AnimatedArtworkPlayback(secondDecoder))
        assertTrue(firstDecoder.closed)
        assertFalse(secondDecoder.closed)

        slot.close()
        assertTrue(secondDecoder.closed)
    }

    private class FakeDecoder(
            private val frameDurations: LongArray,
            override val loopCount: Int = 0
    ) : AnimatedArtworkDecoder {
        override val width = 20
        override val height = 20
        override val frameCount = frameDurations.size
        var closed = false

        override fun frameDurationMillis(frameIndex: Int) = frameDurations[frameIndex]
        override fun seekToFrame(frameIndex: Int) = Unit
        override fun uploadCurrentFrame() = Unit
        override fun updateTexture() = Unit

        override fun close() {
            closed = true
        }
    }
}
