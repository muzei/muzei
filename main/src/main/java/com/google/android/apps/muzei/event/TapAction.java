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

import android.support.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public abstract class TapAction
{
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({NOTHING, SHOW_ORIGINAL_ARTWORK, NEXT_ARTWORK, VIEW_ARTWORK})
    public @interface Value {}

    public static final int NOTHING = 0;
    public static final int SHOW_ORIGINAL_ARTWORK = 1;
    public static final int NEXT_ARTWORK = 2;
    public static final int VIEW_ARTWORK = 3;
}
