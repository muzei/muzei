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

package com.google.android.apps.muzei.util;

import android.graphics.Path;
import android.graphics.PointF;

import java.text.ParseException;

class SvgPathParser {
    private static final int TOKEN_ABSOLUTE_COMMAND = 1;
    private static final int TOKEN_RELATIVE_COMMAND = 2;
    private static final int TOKEN_VALUE = 3;
    private static final int TOKEN_EOF = 4;

    private int mCurrentToken;
    private PointF mCurrentPoint = new PointF();
    private int mLength;
    private int mIndex;
    private String mPathString;

    protected float transformX(float x) {
        return x;
    }

    protected float transformY(float y) {
        return y;
    }

    public Path parsePath(String s) throws ParseException {
        mCurrentPoint.set(Float.NaN, Float.NaN);
        mPathString = s;
        mIndex = 0;
        mLength = mPathString.length();

        PointF tempPoint1 = new PointF();
        PointF tempPoint2 = new PointF();
        PointF tempPoint3 = new PointF();

        Path p = new Path();
        p.setFillType(Path.FillType.WINDING);

        boolean firstMove = true;
        while (mIndex < mLength) {
            char command = consumeCommand();
            boolean relative = (mCurrentToken == TOKEN_RELATIVE_COMMAND);
            switch (command) {
                case 'M':
                case 'm': {
                    // move command
                    boolean firstPoint = true;
                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1,
                                relative && mCurrentPoint.x != Float.NaN);
                        if (firstPoint) {
                            p.moveTo(tempPoint1.x, tempPoint1.y);
                            firstPoint = false;
                            if (firstMove) {
                                mCurrentPoint.set(tempPoint1);
                                firstMove = false;
                            }
                        } else {
                            p.lineTo(tempPoint1.x, tempPoint1.y);
                        }
                    }
                    mCurrentPoint.set(tempPoint1);
                    break;
                }

                case 'C':
                case 'c': {
                    // curve command
                    if (mCurrentPoint.x == Float.NaN) {
                        throw new ParseException("Relative commands require current point", mIndex);
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1, relative);
                        consumeAndTransformPoint(tempPoint2, relative);
                        consumeAndTransformPoint(tempPoint3, relative);
                        p.cubicTo(tempPoint1.x, tempPoint1.y, tempPoint2.x, tempPoint2.y,
                                tempPoint3.x, tempPoint3.y);
                    }
                    mCurrentPoint.set(tempPoint3);
                    break;
                }

                case 'L':
                case 'l': {
                    // line command
                    if (mCurrentPoint.x == Float.NaN) {
                        throw new ParseException("Relative commands require current point", mIndex);
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1, relative);
                        p.lineTo(tempPoint1.x, tempPoint1.y);
                    }
                    mCurrentPoint.set(tempPoint1);
                    break;
                }

                case 'H':
                case 'h': {
                    // horizontal line command
                    if (mCurrentPoint.x == Float.NaN) {
                        throw new ParseException("Relative commands require current point", mIndex);
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        float x = transformX(consumeValue());
                        if (relative) {
                            x += mCurrentPoint.x;
                        }
                        p.lineTo(x, mCurrentPoint.y);
                    }
                    mCurrentPoint.set(tempPoint1);
                    break;
                }

                case 'V':
                case 'v': {
                    // vertical line command
                    if (mCurrentPoint.x == Float.NaN) {
                        throw new ParseException("Relative commands require current point", mIndex);
                    }

                    while (advanceToNextToken() == TOKEN_VALUE) {
                        float y = transformY(consumeValue());
                        if (relative) {
                            y += mCurrentPoint.y;
                        }
                        p.lineTo(mCurrentPoint.x, y);
                    }
                    mCurrentPoint.set(tempPoint1);
                    break;
                }

                case 'Q':
                case 'q': {
                    // curve command
                    if (mCurrentPoint.x == Float.NaN) {
                        throw new ParseException("Relative commands require current point", mIndex);
                    }
                    while (advanceToNextToken() == TOKEN_VALUE) {
                        consumeAndTransformPoint(tempPoint1, relative);
                        consumeAndTransformPoint(tempPoint2, relative);
                        p.quadTo(tempPoint1.x, tempPoint1.y, tempPoint2.x, tempPoint2.y);
                    }
                    mCurrentPoint.set(tempPoint2);
                    break;
                }

                case 'Z':
                case 'z': {
                    // close command
                    p.close();
                    break;
                }
            }

        }

        return p;
    }

    private int advanceToNextToken() {
        while (mIndex < mLength) {
            char c = mPathString.charAt(mIndex);
            if ('a' <= c && c <= 'z') {
                return (mCurrentToken = TOKEN_RELATIVE_COMMAND);
            } else if ('A' <= c && c <= 'Z') {
                return (mCurrentToken = TOKEN_ABSOLUTE_COMMAND);
            } else if (('0' <= c && c <= '9') || c == '.' || c == '-') {
                return (mCurrentToken = TOKEN_VALUE);
            }

            // skip unrecognized character
            ++mIndex;
        }

        return (mCurrentToken = TOKEN_EOF);
    }

    private char consumeCommand() throws ParseException {
        advanceToNextToken();
        if (mCurrentToken != TOKEN_RELATIVE_COMMAND && mCurrentToken != TOKEN_ABSOLUTE_COMMAND) {
            throw new ParseException("Expected command", mIndex);
        }

        return mPathString.charAt(mIndex++);
    }

    private void consumeAndTransformPoint(PointF out, boolean relative) throws ParseException {
        out.x = transformX(consumeValue());
        out.y = transformY(consumeValue());
        if (relative) {
            out.x += mCurrentPoint.x;
            out.y += mCurrentPoint.y;
        }
    }

    private float consumeValue() throws ParseException {
        advanceToNextToken();
        if (mCurrentToken != TOKEN_VALUE) {
            throw new ParseException("Expected value", mIndex);
        }

        boolean start = true;
        boolean seenDot = false;
        int index = mIndex;
        while (index < mLength) {
            char c = mPathString.charAt(index);
            if (!('0' <= c && c <= '9') && (c != '.' || seenDot) && (c != '-' || !start)) {
                // end of value
                break;
            }
            if (c == '.') {
                seenDot = true;
            }
            start = false;
            ++index;
        }

        if (index == mIndex) {
            throw new ParseException("Expected value", mIndex);
        }

        String str = mPathString.substring(mIndex, index);
        try {
            float value = Float.parseFloat(str);
            mIndex = index;
            return value;
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid float value '" + str + "'.", mIndex);
        }
    }
}
