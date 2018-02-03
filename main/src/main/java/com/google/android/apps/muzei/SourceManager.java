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
import android.arch.lifecycle.DefaultLifecycleObserver;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LifecycleRegistry;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.MediatorLiveData;
import android.arch.lifecycle.Observer;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.google.android.apps.muzei.api.MuzeiArtSource;
import com.google.android.apps.muzei.featuredart.FeaturedArtSource;
import com.google.android.apps.muzei.room.MuzeiDatabase;
import com.google.android.apps.muzei.room.Source;
import com.google.android.apps.muzei.room.SourceDao;
import com.google.android.apps.muzei.sync.TaskQueueService;
import com.google.android.apps.muzei.wallpaper.NetworkChangeObserver;
import com.google.firebase.analytics.FirebaseAnalytics;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

/**
 * Class responsible for managing interactions with sources such as subscribing, unsubscribing, and sending actions.
 */
public class SourceManager implements DefaultLifecycleObserver, LifecycleOwner {
    private static final String TAG = "SourceManager";
    private static final String USER_PROPERTY_SELECTED_SOURCE = "selected_source";
    private static final String USER_PROPERTY_SELECTED_SOURCE_PACKAGE = "selected_source_package";
    private Executor mExecutor = Executors.newSingleThreadExecutor();

    private class UpdateSourcesRunnable implements Runnable {
        private final String mPackageName;

        UpdateSourcesRunnable() {
            this(null);
        }

        UpdateSourcesRunnable(String packageName) {
            mPackageName = packageName;
        }

        @Override
        public void run() {
            Intent queryIntent = new Intent(MuzeiArtSource.ACTION_MUZEI_ART_SOURCE);
            if (mPackageName != null) {
                queryIntent.setPackage(mPackageName);
            }
            PackageManager pm = mContext.getPackageManager();
            MuzeiDatabase database = MuzeiDatabase.getInstance(mContext);
            database.beginTransaction();
            HashSet<ComponentName> existingSources = new HashSet<>(mPackageName != null
                    ? database.sourceDao().getSourcesComponentNamesByPackageNameBlocking(mPackageName)
                    : database.sourceDao().getSourceComponentNamesBlocking());
            for (ResolveInfo ri : pm.queryIntentServices(queryIntent, PackageManager.GET_META_DATA)) {
                existingSources.remove(new ComponentName(ri.serviceInfo.packageName,
                        ri.serviceInfo.name));
                updateSourceFromServiceInfo(ri.serviceInfo);
            }
            // Delete sources in the database that have since been removed
            database.sourceDao().deleteAll(existingSources.toArray(new ComponentName[0]));
            database.setTransactionSuccessful();
            database.endTransaction();
        }
    }

    private final BroadcastReceiver mSourcePackageChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            if (intent == null || intent.getData() == null) {
                return;
            }
            final String packageName = intent.getData().getSchemeSpecificPart();
            // Update the sources from the changed package
            mExecutor.execute(new UpdateSourcesRunnable(packageName));
            final PendingResult pendingResult = goAsync();
            final LiveData<Source> sourceLiveData = MuzeiDatabase.getInstance(context).sourceDao()
                    .getCurrentSource();
            sourceLiveData.observeForever(
                    new Observer<Source>() {
                        @Override
                        public void onChanged(@Nullable final Source source) {
                            sourceLiveData.removeObserver(this);
                            if (source != null && TextUtils.equals(packageName, source.componentName.getPackageName())) {
                                try {
                                    mContext.getPackageManager().getServiceInfo(source.componentName, 0);
                                } catch (PackageManager.NameNotFoundException e) {
                                    Log.i(TAG, "Selected source " + source.componentName
                                            + " is no longer available");
                                    selectSource(context, new ComponentName(context, FeaturedArtSource.class));
                                    return;
                                }
                                // Some other change.
                                if (mLifecycle.getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                                    Log.i(TAG, "Source package changed or replaced. Re-subscribing to " +
                                            source.componentName);
                                    subscribe(source);
                                }
                            }
                            pendingResult.finish();
                        }
                    });
        }
    };

    private class SubscriberLiveData extends MediatorLiveData<Source> {
        private Source currentSource = null;

        SubscriberLiveData() {
            addSource(MuzeiDatabase.getInstance(mContext).sourceDao().getCurrentSource(), source -> {
                ComponentName current = currentSource != null ? currentSource.componentName : null;
                ComponentName next = source != null ? source.componentName : null;
                if (Objects.equals(current, next)) {
                    return;
                }
                if (currentSource != null) {
                    unsubscribe(currentSource);
                }
                currentSource = source;
                if (source != null) {
                    subscribe(source);
                    setValue(source);
                } else {
                    // Can't have no source at all, so swap to the default
                    selectSource(mContext, new ComponentName(mContext, FeaturedArtSource.class));
                }
            });
        }

        @Override
        protected void onInactive() {
            super.onInactive();
            if (currentSource != null) {
                unsubscribe(currentSource);
            }
        }
    }

    private final Context mContext;
    private final LifecycleRegistry mLifecycle;

    public SourceManager(Context context) {
        mContext = context;
        mLifecycle = new LifecycleRegistry(this);
        mLifecycle.addObserver(new NetworkChangeObserver(mContext));
        new SubscriberLiveData().observe(this, source -> {
            if (source != null) {
                sendSelectedSourceAnalytics(source.componentName);
                // Ensure the artwork from the newly selected source is downloaded
                context.startService(TaskQueueService.getDownloadCurrentArtworkIntent(context));
            }
        });
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycle;
    }

    @Override
    public void onCreate(@NonNull final LifecycleOwner owner) {
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START);
        // Register for package change events
        IntentFilter packageChangeFilter = new IntentFilter();
        packageChangeFilter.addDataScheme("package");
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageChangeFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        mContext.registerReceiver(mSourcePackageChangeReceiver, packageChangeFilter);
        // Update the available sources in case we missed anything while Muzei was disabled
        mExecutor.execute(new UpdateSourcesRunnable());
    }

    private void updateSourceFromServiceInfo(ServiceInfo info) {
        PackageManager pm = mContext.getPackageManager();
        Bundle metaData = info.metaData;
        ComponentName componentName = new ComponentName(info.packageName, info.name);
        SourceDao sourceDao = MuzeiDatabase.getInstance(mContext).sourceDao();
        Source existingSource = sourceDao.getSourceByComponentNameBlocking(componentName);
        if (!info.isEnabled()) {
            // Disabled sources can't be used
            if (existingSource != null) {
                sourceDao.delete(existingSource);
            }
            return;
        }
        Source source = existingSource != null ? existingSource : new Source(componentName);
        source.label = info.loadLabel(pm).toString();
        source.targetSdkVersion = info.applicationInfo.targetSdkVersion;
        if (info.descriptionRes != 0) {
            try {
                Context packageContext = mContext.createPackageContext(
                        source.componentName.getPackageName(), 0);
                Resources packageRes = packageContext.getResources();
                source.defaultDescription = packageRes.getString(info.descriptionRes);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
                Log.e(TAG, "Can't read package resources for source " + source.componentName);
            }
        }
        source.color = Color.WHITE;
        if (metaData != null) {
            String settingsActivity = metaData.getString("settingsActivity");
            if (!TextUtils.isEmpty(settingsActivity)) {
                source.settingsActivity = ComponentName.unflattenFromString(
                        info.packageName + "/" + settingsActivity);
            }
            String setupActivity = metaData.getString("setupActivity");
            if (!TextUtils.isEmpty(setupActivity)) {
                source.setupActivity = ComponentName.unflattenFromString(
                        info.packageName + "/" + setupActivity);
            }
            source.color = metaData.getInt("color", source.color);
            try {
                float[] hsv = new float[3];
                Color.colorToHSV(source.color, hsv);
                boolean adjust = false;
                if (hsv[2] < 0.8f) {
                    hsv[2] = 0.8f;
                    adjust = true;
                }
                if (hsv[1] > 0.4f) {
                    hsv[1] = 0.4f;
                    adjust = true;
                }
                if (adjust) {
                    source.color = Color.HSVToColor(hsv);
                }
                if (Color.alpha(source.color) != 255) {
                    source.color = Color.argb(255,
                            Color.red(source.color),
                            Color.green(source.color),
                            Color.blue(source.color));
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (existingSource == null) {
            sourceDao.insert(source);
        } else {
            sourceDao.update(source);
        }
    }

    @Override
    public void onDestroy(@NonNull final LifecycleOwner owner) {
        mLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);
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
        new AsyncTask<Void, Void, Source>() {
            @Override
            protected Source doInBackground(final Void... voids) {
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

                return newSource;
            }

            @Override
            protected void onPostExecute(final Source newSource) {
                if (callback != null) {
                    callback.onSourceSelected();
                }
            }
        }.execute();
    }

    private void sendSelectedSourceAnalytics(ComponentName selectedSource) {
        // The current limit for user property values
        final int MAX_VALUE_LENGTH = 36;
        String packageName = selectedSource.getPackageName();
        if (packageName.length() > MAX_VALUE_LENGTH) {
            packageName = packageName.substring(packageName.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(mContext).setUserProperty(USER_PROPERTY_SELECTED_SOURCE_PACKAGE,
                packageName);
        String className = selectedSource.flattenToShortString();
        className = className.substring(className.indexOf('/') + 1);
        if (className.length() > MAX_VALUE_LENGTH) {
            className = className.substring(className.length() - MAX_VALUE_LENGTH);
        }
        FirebaseAnalytics.getInstance(mContext).setUserProperty(USER_PROPERTY_SELECTED_SOURCE,
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
                        // Ensure that we have a valid service before sending the action
                        context.getPackageManager().getServiceInfo(selectedSource, 0);
                        context.startService(new Intent(ACTION_HANDLE_COMMAND)
                                .setComponent(selectedSource)
                                .putExtra(EXTRA_COMMAND_ID, id));
                    } catch (PackageManager.NameNotFoundException | IllegalStateException | SecurityException e) {
                        Log.i(TAG, "Sending action + " + id + " to " + selectedSource
                                + " failed; switching to default.", e);
                        Toast.makeText(context, R.string.source_unavailable, Toast.LENGTH_LONG).show();
                        selectSource(context, new ComponentName(context, FeaturedArtSource.class));
                    }
                }
            }
        });
    }

    private void subscribe(@NonNull Source source) {
        ComponentName selectedSource = source.componentName;
        try {
            // Ensure that we have a valid service before subscribing
            mContext.getPackageManager().getServiceInfo(selectedSource, 0);
            mContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(mContext, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, selectedSource.flattenToShortString()));
        } catch (PackageManager.NameNotFoundException | IllegalStateException | SecurityException e) {
            Log.i(TAG, "Selected source " + selectedSource
                    + " is no longer available; switching to default.", e);
            Toast.makeText(mContext, R.string.source_unavailable, Toast.LENGTH_LONG).show();
            selectSource(mContext, new ComponentName(mContext, FeaturedArtSource.class));
        }
    }

    private void unsubscribe(@NonNull Source source) {
        ComponentName selectedSource = source.componentName;
        try {
            // Ensure that we have a valid service before subscribing
            mContext.getPackageManager().getServiceInfo(selectedSource, 0);
            mContext.startService(new Intent(ACTION_SUBSCRIBE)
                    .setComponent(selectedSource)
                    .putExtra(EXTRA_SUBSCRIBER_COMPONENT,
                            new ComponentName(mContext, SourceSubscriberService.class))
                    .putExtra(EXTRA_TOKEN, (String) null));
        } catch (PackageManager.NameNotFoundException | IllegalStateException | SecurityException e) {
            Log.i(TAG, "Unsubscribing to " + selectedSource
                    + " failed.", e);
        }
    }
}
