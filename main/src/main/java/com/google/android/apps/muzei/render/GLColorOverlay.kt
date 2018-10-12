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

import android.graphics.Color
import android.opengl.GLES20

import java.nio.FloatBuffer

internal class GLColorOverlay {

    companion object {
        private const val VERTEX_SHADER_CODE = "" +
                // This matrix member variable provides a hook to manipulate
                // the coordinates of the objects that use this vertex shader
                "uniform mat4 uMVPMatrix;" +
                "attribute vec4 aPosition;" +
                "void main(){" +
                "  gl_Position = uMVPMatrix * aPosition;" +
                "}"

        private const val FRAGMENT_SHADER_CODE = "" +
                "precision mediump float;" +
                "uniform sampler2D uTexture;" +
                "uniform vec4 uColor;" +
                "void main(){" +
                "  gl_FragColor = uColor;" +
                "}"

        // number of coordinates per vertex in this array
        private const val COORDS_PER_VERTEX = 3
        private const val VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * GLUtil.BYTES_PER_FLOAT

        private var PROGRAM_HANDLE: Int = 0
        private var ATTRIB_POSITION_HANDLE: Int = 0
        private var UNIFORM_COLOR_HANDLE: Int = 0
        private var UNIFORM_MVP_MATRIX_HANDLE: Int = 0

        fun initGl() {
            // Initialize shaders and create/link program
            val vertexShaderHandle = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE)
            val fragShaderHandle = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE)

            PROGRAM_HANDLE = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderHandle, null)
            ATTRIB_POSITION_HANDLE = GLES20.glGetAttribLocation(PROGRAM_HANDLE, "aPosition")
            UNIFORM_MVP_MATRIX_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uMVPMatrix")
            UNIFORM_COLOR_HANDLE = GLES20.glGetUniformLocation(PROGRAM_HANDLE, "uColor")
        }
    }

    private val vertices = floatArrayOf(
            -1f, 1f, 0f, // top left
            -1f, -1f, 0f, // bottom left
            1f, -1f, 0f, // bottom right

            -1f, 1f, 0f, // top left
            1f, -1f, 0f, // bottom right
            1f, 1f, 0f)// top right

    private val vertexBuffer: FloatBuffer = GLUtil.asFloatBuffer(vertices)

    internal var color = 0

    fun draw(mvpMatrix: FloatArray) {
        // Add program to OpenGL ES environment
        GLES20.glUseProgram(PROGRAM_HANDLE)

        // Pass in the vertex information
        GLES20.glEnableVertexAttribArray(ATTRIB_POSITION_HANDLE)
        GLES20.glVertexAttribPointer(ATTRIB_POSITION_HANDLE,
                COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE_BYTES, vertexBuffer)

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(UNIFORM_MVP_MATRIX_HANDLE, 1, false, mvpMatrix, 0)
        GLUtil.checkGlError("glUniformMatrix4fv")

        // Set the alpha
        val r = Color.red(color) * 1f / 255
        val g = Color.green(color) * 1f / 255
        val b = Color.blue(color) * 1f / 255
        val a = Color.alpha(color) * 1f / 255
        GLES20.glUniform4f(UNIFORM_COLOR_HANDLE, r, g, b, a)

        // Draw the triangle
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.size / COORDS_PER_VERTEX)

        GLES20.glDisableVertexAttribArray(ATTRIB_POSITION_HANDLE)
    }
}
