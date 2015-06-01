/*
 * Copyright 2014 Devmil Solutions
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
package com.google.android.apps.muzei.event;

public enum TapAction
{
    Nothing(0),
    ShowOriginalArtwork(1),
    NextArtwork(2),
    ViewArtwork(3);


    //Enum implementation

    private int mCode;

    private TapAction(int code)
    {
        mCode = code;
    }

    public int getCode()
    {
        return mCode;
    }

    public static TapAction fromCode(int code)
    {
        switch(code)
        {
            case 1:
                return ShowOriginalArtwork;
            case 2:
                return NextArtwork;
            case 3:
                return ViewArtwork;
            default:
                return Nothing;
        }
    }
}
