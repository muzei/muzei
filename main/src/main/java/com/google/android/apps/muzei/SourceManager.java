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

package com.google.android.apps.muzei;

import android.annotation.SuppressLint;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.featuredart.FeaturedArtSource;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

/**
 * Class responsible for managing interactions with sources such as subscribing, unsubscribing, and sending actions.
 */
public class SourceManager implements LifecycleObserver {
    private static final String TAG = "SourceManager";
    private static final String USER_PROPERTY_SELECTED_SOURCE = "selected_source";
    private static final String USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package";

    private final Context mContext;

    private SourcePackageChangeReceiver mSourcePackageChangeReceiver;

    public SourceManager(Context context) {
        mContext = context;
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    public void subscribeToSelectedSource() {
        // Register for package change events
        mSourcePackageChangeReceiver = new SourcePackageChangeReceiver();
        IntentFilter packageChangeFilter = new IntentFilter();
        packageChangeFilter.addDataScheme("package");
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mContext.registerReceiver(mSourcePackageChangeReceiver, packageChangeFilter);

        final LiveData<Source> sourceLiveData = MuzeiDatabase.getInstance(mContext).sourceDao().getCurrentSource();
        sourceLiveData.observeForever(new Observer<Source>() {
            @Override
            public void onChanged(@Nullable final Source source) {
                sourceLiveData.removeObserver(this);
                if (source != null) {
                    subscribe(mContext, source);
                } else {
                    // Select the default source
                    selectSource(mContext, new ComponentName(mContext, FeaturedArtSource.class));
                }
            }
        });
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void unsubscribeToSelectedSource() {
        final LiveData<Source> sourceLiveData = MuzeiDatabase.getInstance(mContext).sourceDao().getCurrentSource();
        sourceLiveData.observeForever(new Observer<Source>() {
            @Override
            public void onChanged(@Nullable final Source source) {
                sourceLiveData.removeObserver(this);
                if (source != null) {
                    unsubscribe(mContext, source);
                }
            }
        });
        mContext.unregisterReceiver(mSourcePackageChangeReceiver);
    }

    public interface Callback {
        void onSourceSelected();
    }

    public static void selectSource(Context context, @NonNull ComponentName source) {
        selectSource(context, source, null);
    }

    @SuppressLint("StaticFieldLeak")
    public static void selectSource(final Context context, @NonNull final ComponentName source,
                                    @Nullable final Callback callback) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(final Void... voids) {
                MuzeiDatabase database = MuzeiDatabase.getInstance(context);
                Source selectedSource = database.sourceDao().getCurrentSourceBlocking();
                if (selectedSource != null && source.equals(selectedSource.componentName)) {
                    return null;
                }

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Source " + source + " selected.");
                }

                database.beginTransaction();
                if (selectedSource != null) {
                    unsubscribe(context, selectedSource);

                    // Unselect the old source
                    selectedSource.selected = false;
                    database.sourceDao().update(selectedSource);
                }

                // Select the new source
                Source newSource = database.sourceDao().getSourceByComponentNameBlocking(source);
                if (newSource != null) {
                    newSource.selected = true;
                    database.sourceDao().update(newSource);
                } else {
                    newSource = new Source(source);
                    newSource.selected = true;
                    database.sourceDao().insert(newSource);
                }

                database.setTransactionSuccessful();
                database.endTransaction();
                sendSelectedSourceAnalytics(context, source);

                subscribe(context, newSource);

                // Ensure the artwork from the newly selected source is downloaded
                context.startService(TaskQueueService.getDownloadCurrentArtworkIntent(context));
                return null;
            }

            @Override
            protected void onPostExecute(final Void aVoid) {
                if (callback != null) {
                    callback.onSourceSelected();
                }
            }
        }.execute();
    }

    private static void sendSelectedSourceAnalytics(Context context, ComponentName selectedSource) {
        // The current limit for user property values
        final int MAX_VALUE_LENGTH = 36;
        String packageName = selectedSource.getPackageName();
        if (packageName.length() > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName);
        String className = selectedSource.flattenToShortString();
        className = className.substring(className.indexOf('/')+1);
        if (className.length() > MAX_VALUE_LENGTH) {
            className = className.substring(className.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(context).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
                className);
    }

    public static void sendAction(final Context context, final int id) {
        final LiveData<Source> sourceLiveData = MuzeiDatabase.getInstance(context).sourceDao().getCurrentSource();
        sourceLiveData.observeForever(new Observer<Source>() {
            @Override
            public void onChanged(@Nullable final Source source) {
                sourceLiveData.removeObserver(this);
                if (source != null) {
                    ComponentName selectedSource = source.componentName;
                    try {
                        context.startService(new Intent(ACTION_HANDLE_COMMAND)
                                .setComponent(selectedSource)
                                .putExtra(EXTRA_COMMAND_ID, id));
                    } catch (IllegalStateException|SecurityException e) {
                        Log.i(TAG, "Sending action + " + id + " to " + selectedSource
                                + " failed; switching to default.", e);
                        Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_LONG).show();
                        selectSource(context, new ComponentName(context, FeaturedArtSource.class));
                    }
                }
            }
        });
    }

    static void subscribe(Context context, @NonNull Source source) {
        // Migrate any legacy data to the ContentProvider
        ComponentName selectedSource = source.componentName;
        try {
            // Ensure that we have a valid service before subscribing
            context.getPackageManager().getServiceInfo(selectedSource, 0);
            context.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(context, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()));
        } catch (PackageManager.NameNotFoundException|IllegalStateException|SecurityException e) {
            Log.i(TAG, "Selected source " + selectedSource
                    + " is no longer available; switching to default.", e);
            Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_LONG).show();
            selectSource(context, new ComponentName(context, FeaturedArtSource.class));
        }
    }

    private static void unsubscribe(Context context, @NonNull Source source) {
        try {
            context.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(source.componentName)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(context, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, (String) null));
        } catch (IllegalStateException e) {
            Log.i(TAG, "Unsubscribing to " + source.componentName
                    + " failed.", e);
        }
    }
}
