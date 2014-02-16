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

/**
 * Created by devmil on 15.02.14.
 */
public class DoubleTapActionChangedEvent {

    public enum DoubleTapAction
    {
        ShowOriginalArtwork(1),
        NextArtwork(2);

        //Enum implementation

        private int mCode;

        private DoubleTapAction(int code)
        {
            mCode = code;
        }

        public int getCode()
        {
            return mCode;
        }

        public static DoubleTapAction fromCode(int code)
        {
            switch(code)
            {
                case 2:
                    return NextArtwork;
                default:
                    return ShowOriginalArtwork;
            }
        }
    }

    private DoubleTapAction mNewAction;

    public DoubleTapActionChangedEvent(DoubleTapAction newAction)
    {
        mNewAction = newAction;
    }

    public DoubleTapAction getNewAction() {
        return mNewAction;
    }
}
