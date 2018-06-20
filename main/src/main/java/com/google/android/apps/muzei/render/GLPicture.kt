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

package com.google.android.apps.muzei.render

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.opengl.GLES20
import com.google.android.apps.muzei.util.divideRoundUp
import java.nio.FloatBuffer

internal fun Bitmap.toGLPicture(): GLPicture? {
    if (width == 0 || height == 0) {
        return null
    }
    return GLPicture(this)
}

internal class GLPicture @SuppressLint("CheckResult") internal constructor(
        bitmap: Bitmap
) {

    companion object {
        private const val VERTEX_SHADER_CODE = "" +
                // This matrix member variable provides a hook to manipulate
                // the coordinates of the objects that use this vertex shader
                "uniform mat4 uMVPMatrix;" +
                "attribute vec4 aPosition;" +
                "attribute vec2 aTexCoords;" +
                "varying vec2 vTexCoords;" +
                "void main(){" +
                "  vTexCoords = aTexCoords;" +
                "  gl_Position = uMVPMatrix * aPosition;" +
                "}"

        private const val FRAGMENT_SHADER_CODE = "" +
                "precision mediump float;" +
                "uniform sampler2D uTexture;" +
                "uniform float uAlpha;" +
                "varying vec2 vTexCoords;" +
                "void main(){" +
                "  gl_FragColor = texture2D(uTexture, vTexCoords);" +
                "  gl_FragColor.a = uAlpha;" +
                "}"

        // number of coordinates per vertex in this array
        private const val COORDS_PER_VERTEX = 3
        private const val VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * GLUtil.BYTES_PER_FLOAT
        private const val VERTICES = 6 // TL, BL, BR, TL, BR, TR

        // S, T (or X, Y)
        private const val COORDS_PER_TEXTURE_VERTEX = 2
        private const val TEXTURE_VERTEX_STRIDE_BYTES = COORDS_PER_TEXTURE_VERTEX * GLUtil.BYTES_PER_FLOAT

        private val SQUARE_TEXTURE_VERTICES = floatArrayOf(0f, 0f, // top left
                0f, 1f, // bottom left
                1f, 1f, // bottom right

                0f, 0f, // top left
                1f, 1f, // bottom right
                1f, 0f)// top right

        private var PROGRAM_HANDLE: Int = 0
        private var ATTRIB_POSITION_HANDLE: Int = 0
        private var ATTRIB_TEXTURE_COORDS_HANDLE: Int = 0
        private var UNIFORM_ALPHA_HANDLE: Int = 0
        private var UNIFORM_TEXTURE_HANDLE: Int = 0
        private var UNIFORM_MVP_MATRIX_HANDLE: Int = 0

        private var TILE_SIZE: Int = 0

        fun initGl() {
            // Initialize shaders and create/link program
            val vertexShaderHandle = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
            val fragShaderHandle = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

            PROGRAM_HANDLE = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderHandle, null)
            ATTRIB_POSITION_HANDLE = GLES20.glGetAttribLocation(PROGRAM_HANDLE, "aPosition")
            ATTRIB_TEXTURE_COORDS_HANDLE = GLES20.glGetAttribLocation(PROGRAM_HANDLE, "aTexCoords")
            UNIFORM_MVP_MATRIX_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uMVPMatrix")
            UNIFORM_TEXTURE_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uTexture")
            UNIFORM_ALPHA_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uAlpha")

            // Compute max texture size
            val maxTextureSize = IntArray(1)
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTextureSize, 0)
            TILE_SIZE = Math.min(512, maxTextureSize[0])
        }
    }

    private val vertices = FloatArray(COORDS_PER_VERTEX * VERTICES)
    private val vertexBuffer: FloatBuffer = GLUtil.newFloatBuffer(vertices.size)
    private val textureCoordsBuffer: FloatBuffer = GLUtil.asFloatBuffer(SQUARE_TEXTURE_VERTICES)

    private val numColumns: Int
    private val numRows: Int
    private val width = bitmap.width
    private val height = bitmap.height
    private val textureHandles: IntArray

    init {
        val leftoverHeight = height % TILE_SIZE

        // Load m x n textures
        numColumns = width.divideRoundUp(TILE_SIZE)
        numRows = height.divideRoundUp(TILE_SIZE)

        textureHandles = IntArray(numColumns * numRows)
        if (numColumns == 1 && numRows == 1) {
            textureHandles[0] = GLUtil.loadTexture(bitmap)
        } else {
            val rect = Rect()
            for (y in 0 until numRows) {
                for (x in 0 until numColumns) {
                    rect.set(x * TILE_SIZE,
                            (numRows - y - 1) * TILE_SIZE,
                            (x + 1) * TILE_SIZE,
                            (numRows - y) * TILE_SIZE)
                    // The bottom tiles must be full tiles for drawing, so only allow edge tiles
                    // at the top
                    if (leftoverHeight > 0) {
                        rect.offset(0, -TILE_SIZE + leftoverHeight)
                    }
                    rect.intersect(0, 0, width, height)
                    val subBitmap = Bitmap.createBitmap(bitmap,
                            rect.left, rect.top, rect.width(), rect.height())
                    textureHandles[y * numColumns + x] = GLUtil.loadTexture(subBitmap)
                    subBitmap.recycle()
                }
            }
        }
    }

    fun draw(mvpMatrix: FloatArray, alpha: Float) {
        // Add program to OpenGL ES environment
        GLES20.glUseProgram(PROGRAM_HANDLE)

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(UNIFORM_MVP_MATRIX_HANDLE, 1, false, mvpMatrix, 0)
        GLUtil.checkGlError("glUniformMatrix4fv")

        // Set up vertex buffer
        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION_HANDLE)
        GLES20.glVertexAttribPointer(ATTRIB_POSITION_HANDLE,
                COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE_BYTES, vertexBuffer)

        // Set up texture stuff
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glUniform1i(UNIFORM_TEXTURE_HANDLE, 0)
        GLES20.glVertexAttribPointer(ATTRIB_TEXTURE_COORDS_HANDLE,
                COORDS_PER_TEXTURE_VERTEX, GLES20.GL_FLOAT, false,
                TEXTURE_VERTEX_STRIDE_BYTES, textureCoordsBuffer)
        GLES20.glEnableVertexAttribArray(ATTRIB_TEXTURE_COORDS_HANDLE)

        // Set the alpha
        GLES20.glUniform1f(UNIFORM_ALPHA_HANDLE, alpha)

        // Draw tiles
        for (y in 0 until numRows) {
            for (x in 0 until numColumns) {
                // Pass in the vertex information
                vertices[9] = Math.min(-1 + 2f * x.toFloat() * TILE_SIZE.toFloat() / width, 1f)
                vertices[3] = vertices[9]
                vertices[0] = vertices[3] // left
                vertices[16] = Math.min(-1 + 2f * (y + 1).toFloat() * TILE_SIZE.toFloat() / height, 1f)
                vertices[10] = vertices[16]
                vertices[1] = vertices[10] // top
                vertices[15] = Math.min(-1 + 2f * (x + 1).toFloat() * TILE_SIZE.toFloat() / width, 1f)
                vertices[12] = vertices[15]
                vertices[6] = vertices[12] // right
                vertices[13] = Math.min(-1 + 2f * y.toFloat() * TILE_SIZE.toFloat() / height, 1f)
                vertices[7] = vertices[13]
                vertices[4] = vertices[7] // bottom
                vertexBuffer.put(vertices)
                vertexBuffer.position(0)

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,
                        textureHandles[y * numColumns + x])
                GLUtil.checkGlError("glBindTexture")

                // Draw the two triangles
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.size / COORDS_PER_VERTEX)
            }
        }

        GLES20.glDisableVertexAttribArray(ATTRIB_POSITION_HANDLE)
        GLES20.glDisableVertexAttribArray(ATTRIB_TEXTURE_COORDS_HANDLE)
    }

    fun destroy() {
        GLES20.glDeleteTextures(textureHandles.size, textureHandles, 0)
        GLUtil.checkGlError("Destroy picture")
    }
}
