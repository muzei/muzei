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

package com.google.android.apps.muzei.util

import android.graphics.Path
import android.graphics.PointF

import java.text.ParseException

internal class SvgPathParser(
        private val transformX: (x: Float) -> Float = { it },
        private val transformY: (y: Float) -> Float = { it }
) {

    companion object {
        private const val TOKEN_ABSOLUTE_COMMAND = 1
        private const val TOKEN_RELATIVE_COMMAND = 2
        private const val TOKEN_VALUE = 3
        private const val TOKEN_EOF = 4
    }

    private lateinit var pathString: String
    private var length: Int = 0
    private var currentToken: Int = 0
    private val currentPoint = PointF()
    private var currentIndex: Int = 0

    @Throws(ParseException::class)
    fun parsePath(s: String): Path {
        currentPoint.set(java.lang.Float.NaN, java.lang.Float.NaN)
        pathString = s
        currentIndex = 0
        length = pathString.length

        val tempPoint1 = PointF()
        val tempPoint2 = PointF()
        val tempPoint3 = PointF()

        val p = Path().apply {
            fillType = Path.FillType.WINDING
        }

        var firstMove = true
        while (currentIndex < length) {
            val command = consumeCommand()
            val relative = currentToken == TOKEN_RELATIVE_COMMAND
            when (command) {
                'M', 'm' -> {
                    // move command
                    var firstPoint = true
                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1,
                                relative && currentPoint.x != java.lang.Float.NaN)
                        if (firstPoint) {
                            p.moveTo(tempPoint1.x, tempPoint1.y)
                            firstPoint = false
                            if (firstMove) {
                                currentPoint.set(tempPoint1)
                                firstMove = false
                            }
                        } else {
                            p.lineTo(tempPoint1.x, tempPoint1.y)
                        }
                    }
                    currentPoint.set(tempPoint1)
                }

                'C', 'c' -> {
                    // curve command
                    if (currentPoint.x == java.lang.Float.NaN) {
                        throw ParseException("Relative commands require current point", currentIndex)
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1, relative)
                        consumeAndTransformPoint(tempPoint2, relative)
                        consumeAndTransformPoint(tempPoint3, relative)
                        p.cubicTo(tempPoint1.x, tempPoint1.y, tempPoint2.x, tempPoint2.y,
                                tempPoint3.x, tempPoint3.y)
                    }
                    currentPoint.set(tempPoint3)
                }

                'L', 'l' -> {
                    // line command
                    if (currentPoint.x == java.lang.Float.NaN) {
                        throw ParseException("Relative commands require current point", currentIndex)
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1, relative)
                        p.lineTo(tempPoint1.x, tempPoint1.y)
                    }
                    currentPoint.set(tempPoint1)
                }

                'H', 'h' -> {
                    // horizontal line command
                    if (currentPoint.x == java.lang.Float.NaN) {
                        throw ParseException("Relative commands require current point", currentIndex)
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        var x = transformX(consumeValue())
                        if (relative) {
                            x += currentPoint.x
                        }
                        p.lineTo(x, currentPoint.y)
                    }
                    currentPoint.set(tempPoint1)
                }

                'V', 'v' -> {
                    // vertical line command
                    if (currentPoint.x == java.lang.Float.NaN) {
                        throw ParseException("Relative commands require current point", currentIndex)
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        var y = transformY(consumeValue())
                        if (relative) {
                            y += currentPoint.y
                        }
                        p.lineTo(currentPoint.x, y)
                    }
                    currentPoint.set(tempPoint1)
                }

                'Q', 'q' -> {
                    // curve command
                    if (currentPoint.x == java.lang.Float.NaN) {
                        throw ParseException("Relative commands require current point", currentIndex)
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1, relative)
                        consumeAndTransformPoint(tempPoint2, relative)
                        p.quadTo(tempPoint1.x, tempPoint1.y, tempPoint2.x, tempPoint2.y)
                    }
                    currentPoint.set(tempPoint2)
                }

                'Z', 'z' -> {
                    // close command
                    p.close()
                }
            }
        }
        return p
    }

    private fun advanceToNextToken(): Int {
        while (currentIndex < length) {
            when (pathString[currentIndex]) {
                in 'a'..'z' -> {
                    currentToken = TOKEN_RELATIVE_COMMAND
                    return currentToken
                }
                in 'A'..'Z' -> {
                    currentToken = TOKEN_ABSOLUTE_COMMAND
                    return currentToken
                }
                in '0'..'9', '.', '-' -> {
                    currentToken = TOKEN_VALUE
                    return currentToken
                }
            // skip unrecognized character
                else -> ++currentIndex
            }
        }

        currentToken = TOKEN_EOF
        return currentToken
    }

    @Throws(ParseException::class)
    private fun consumeCommand(): Char {
        advanceToNextToken()
        if (currentToken != TOKEN_RELATIVE_COMMAND && currentToken != TOKEN_ABSOLUTE_COMMAND) {
            throw ParseException("Expected command", currentIndex)
        }

        return pathString[currentIndex++]
    }

    @Throws(ParseException::class)
    private fun consumeAndTransformPoint(out: PointF, relative: Boolean) {
        out.x = transformX(consumeValue())
        out.y = transformY(consumeValue())
        if (relative) {
            out.x += currentPoint.x
            out.y += currentPoint.y
        }
    }

    @Throws(ParseException::class)
    private fun consumeValue(): Float {
        advanceToNextToken()
        if (currentToken != TOKEN_VALUE) {
            throw ParseException("Expected value", currentIndex)
        }

        var start = true
        var seenDot = false
        var index = currentIndex
        while (index < length) {
            val c = pathString[index]
            if (c !in '0'..'9' && (c != '.' || seenDot) && (c != '-' || !start)) {
                // end of value
                break
            }
            if (c == '.') {
                seenDot = true
            }
            start = false
            ++index
        }

        if (index == currentIndex) {
            throw ParseException("Expected value", currentIndex)
        }

        val str = pathString.substring(currentIndex, index)
        try {
            val value = java.lang.Float.parseFloat(str)
            currentIndex = index
            return value
        } catch (e: NumberFormatException) {
            throw ParseException("Invalid float value '$str'.", currentIndex)
        }
    }
}
