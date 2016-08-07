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

package com.google.android.apps.muzei.render;

import android.graphics.Color;
import android.opengl.GLES20;

import java.nio.FloatBuffer;

class GLColorOverlay {
    private static final String VERTEX_SHADER_CODE = "" +
            // This matrix member variable provides a hook to manipulate
            // the coordinates of the objects that use this vertex shader
            "uniform mat4 uMVPMatrix;" +
            "attribute vec4 aPosition;" +
            "void main(){" +
            "  gl_Position = uMVPMatrix * aPosition;" +
            "}";

    private static final String FRAGMENT_SHADER_CODE = "" +
            "precision mediump float;" +
            "uniform sampler2D uTexture;" +
            "uniform vec4 uColor;" +
            "void main(){" +
            "  gl_FragColor = uColor;" +
            "}";

    // number of coordinates per vertex in this array
    private static final int COORDS_PER_VERTEX = 3;
    private static final int VERTEX_STRIDE_BYTES = COORDS_PER_VERTEX * GLUtil.BYTES_PER_FLOAT;

    private float mVertices[] = {
            -1,  1, 0,   // top left
            -1, -1, 0,   // bottom left
             1, -1, 0,   // bottom right

            -1,  1, 0,   // top left
             1, -1, 0,   // bottom right
             1,  1, 0,   // top right
    };

    private int mColor = 0;

    private FloatBuffer mVertexBuffer;

    private static int sProgramHandle;
    private static int sAttribPositionHandle;
    private static int sUniformColorHandle;
    private static int sUniformMVPMatrixHandle;

    public GLColorOverlay() {
        mVertexBuffer = GLUtil.asFloatBuffer(mVertices);
    }

    public static void initGl() {
        // Initialize shaders and create/link program
        int vertexShaderHandle = GLUtil.loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_CODE);
        int fragShaderHandle = GLUtil.loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_CODE);

        sProgramHandle = GLUtil.createAndLinkProgram(vertexShaderHandle, fragShaderHandle, null);
        sAttribPositionHandle = GLES20.glGetAttribLocation(sProgramHandle, "aPosition");
        sUniformMVPMatrixHandle = GLES20.glGetUniformLocation(sProgramHandle, "uMVPMatrix");
        sUniformColorHandle = GLES20.glGetUniformLocation(sProgramHandle, "uColor");
    }

    public void draw(float[] mvpMatrix) {
        // Add program to OpenGL ES environment
        GLES20.glUseProgram(sProgramHandle);

        // Pass in the vertex information
        GLES20.glEnableVertexAttribArray(sAttribPositionHandle);
        GLES20.glVertexAttribPointer(sAttribPositionHandle,
                COORDS_PER_VERTEX, GLES20.GL_FLOAT, false,
                VERTEX_STRIDE_BYTES, mVertexBuffer);

        // Apply the projection and view transformation
        GLES20.glUniformMatrix4fv(sUniformMVPMatrixHandle, 1, false, mvpMatrix, 0);
        GLUtil.checkGlError("glUniformMatrix4fv");

        // Set the alpha
        float r = Color.red(mColor) * 1f / 255;
        float g = Color.green(mColor) * 1f / 255;
        float b = Color.blue(mColor) * 1f / 255;
        float a = Color.alpha(mColor) * 1f / 255;
        GLES20.glUniform4f(sUniformColorHandle, r, g, b, a);

        // Draw the triangle
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mVertices.length / COORDS_PER_VERTEX);

        GLES20.glDisableVertexAttribArray(sAttribPositionHandle);
    }

    public void setColor(int color) {
        mColor = color;
    }
}
