/*
 * Copyright 2017 Google Inc.
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

package com.google.android.apps.muzei.widget;

import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;

import com.google.android.apps.muzei.api.MuzeiContract;

/**
 * LifecycleObserver which updates the widget when the artwork changes
 */
public class WidgetUpdater implements LifecycleObserver {
    private final Context mContext;
    private ContentObserver mWidgetContentObserver;

    public WidgetUpdater(Context context) {
        mContext = context;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void registerContentObserver() {
        // Set up a ContentObserver to update widgets whenever the artwork changes
        mWidgetContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(final boolean selfChange, final Uri uri) {
                new AppWidgetUpdateTask(mContext, false)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        };
        mContext.getContentResolver().registerContentObserver(MuzeiContract.Artwork.CONTENT_URI,
                true, mWidgetContentObserver);
        mContext.getContentResolver().registerContentObserver(MuzeiContract.Sources.CONTENT_URI,
                true, mWidgetContentObserver);
        // Update the widget now that the wallpaper is active to enable the 'Next' button if supported
        mWidgetContentObserver.onChange(true);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void unregisterContentObserver() {
        mContext.getContentResolver().unregisterContentObserver(mWidgetContentObserver);
        // Update the widget one last time to disable the 'Next' button until Muzei is reactivated
        new AppWidgetUpdateTask(mContext, false)
                .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
