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

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log

import net.nurik.roman.muzei.BuildConfig

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

object GLUtil {
    private const val TAG = "GLUtil"

    const val BYTES_PER_FLOAT = 4

    fun loadShader(type: Int, shaderCode: String): Int {
        // create a vertex shader type (GLES20.GL_VERTEX_SHADER)
        // or a fragment shader type (GLES20.GL_FRAGMENT_SHADER)
        val shaderHandle = GLES20.glCreateShader(type)

        // add the source code to the shader and compile it
        GLES20.glShaderSource(shaderHandle, shaderCode)
        GLES20.glCompileShader(shaderHandle)
        checkGlError("glCompileShader")
        return shaderHandle
    }

    fun createAndLinkProgram(
            vertexShaderHandle: Int,
            fragShaderHandle: Int,
            attributes: Array<String>?
    ): Int {
        val programHandle = GLES20.glCreateProgram()
        GLUtil.checkGlError("glCreateProgram")
        GLES20.glAttachShader(programHandle, vertexShaderHandle)
        GLES20.glAttachShader(programHandle, fragShaderHandle)
        if (attributes != null) {
            val size = attributes.size
            for (i in 0 until size) {
                GLES20.glBindAttribLocation(programHandle, i, attributes[i])
            }
        }
        GLES20.glLinkProgram(programHandle)
        GLUtil.checkGlError("glLinkProgram")
        GLES20.glDeleteShader(vertexShaderHandle)
        GLES20.glDeleteShader(fragShaderHandle)
        return programHandle
    }

    fun loadTexture(bitmap: Bitmap): Int {
        val textureHandle = IntArray(1)

        GLES20.glGenTextures(1, textureHandle, 0)
        GLUtil.checkGlError("glGenTextures")

        if (textureHandle[0] != 0) {
            // Bind to the texture in OpenGL
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureHandle[0])

            // Set filtering
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S,
                    GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                    GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                    GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER,
                    GLES20.GL_LINEAR)

            // Load the bitmap into the bound texture.
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            GLUtil.checkGlError("texImage2D")
        }

        if (textureHandle[0] == 0) {
            Log.e(TAG, "Error loading texture (empty texture handle)")
            if (BuildConfig.DEBUG) {
                throw RuntimeException("Error loading texture (empty texture handle).")
            }
        }

        return textureHandle[0]
    }

    fun checkGlError(glOperation: String) {
        var error: Int
        while (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "$glOperation: glError $error")
            if (BuildConfig.DEBUG) {
                throw RuntimeException("$glOperation: glError $error")
            }
        }
    }

    fun asFloatBuffer(array: FloatArray): FloatBuffer =
            newFloatBuffer(array.size).apply {
                put(array)
                position(0)
            }

    fun newFloatBuffer(size: Int): FloatBuffer =
            ByteBuffer.allocateDirect(size * BYTES_PER_FLOAT)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer().apply { position(0) }
}
