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

import android.app.AlarmManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Pair;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.internal.SourceState;
import com.google.android.apps.muzei.event.ArtworkLoadingStateChangedEvent;
import com.google.android.apps.muzei.event.CurrentArtworkDownloadedEvent;
import com.google.android.apps.muzei.render.BitmapRegionLoader;
import com.google.android.apps.muzei.util.IOUtil;
import com.google.android.apps.muzei.util.LogUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import de.greenrobot.event.EventBus;

import static com.google.android.apps.muzei.util.LogUtil.LOGE;
import static com.google.android.apps.muzei.util.LogUtil.LOGW;

public class ArtworkCache {
    private static final String TAG = LogUtil.makeLogTag(ArtworkCache.class);

    private Context mApplicationContext;
    private File mAppCacheRoot;
    private File mArtCacheRoot;

    private static final int MAX_CACHE_SIZE = 3; // 3 items per source

    private static final String PREF_ARTWORK_DOWNLOAD_ATTEMPT = "artwork_download_attempt";

    private static ArtworkCache sInstance;

    public static ArtworkCache getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new ArtworkCache(context);
        }

        return sInstance;
    }

    private ArtworkCache(Context context) {
        mApplicationContext = context.getApplicationContext();

        // TODO: instead of best available, optimize for stable location since these aren't
        // meant to be too temporary
        mAppCacheRoot = IOUtil.getBestAvailableCacheRoot(mApplicationContext);
        mArtCacheRoot = new File(mAppCacheRoot, "artcache");
    }

    public synchronized void maybeDownloadCurrentArtworkSync() {
        SourceManager sm = SourceManager.getInstance(mApplicationContext);
        ComponentName selectedSource = sm.getSelectedSource();
        SourceState selectedSourceState = sm.getSelectedSourceState();
        Artwork currentArtwork = selectedSourceState != null
                ? selectedSourceState.getCurrentArtwork() : null;
        if (currentArtwork == null) {
            return;
        }

        File destFile = getArtworkCacheFile(selectedSource, currentArtwork);
        if (destFile == null) {
            return;
        }

        if (destFile.exists() && destFile.length() > 0) {
            EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(false, false));
            EventBus.getDefault().post(new CurrentArtworkDownloadedEvent());
            return;
        }

        // ensure cache root for this source exists
        destFile.getParentFile().mkdirs();

        EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(true, false));

        InputStream in;
        try {
            in = IOUtil.openUri(mApplicationContext, currentArtwork.getImageUri(), "image/");
        } catch (IOUtil.OpenUriException e) {
            LOGE(TAG, "Error downloading current artwork. URI: " + currentArtwork.getImageUri(), e);
            if (e.isRetryable()) {
                scheduleRetryArtworkDownload();
            }
            EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(false, true));
            return;
        }

        cancelArtworkDownloadRetries();

        // Input stream successfully opened. Save to cache file
        try {
            File tempFile = new File(mArtCacheRoot, "temp.download");
            IOUtil.readFullyWriteToFile(in, tempFile);
            destFile.delete();
            if (!tempFile.renameTo(destFile)) {
                throw new IOException("Couldn't move temp artwork file to final cache location.");
            }
            // Attempt to parse the newly downloaded file as an image, ensuring it is in a valid format
            BitmapRegionLoader.newInstance(new FileInputStream(destFile));
        } catch (IOException e) {
            LOGE(TAG, "Error caching and loading the current artwork. URI: " + currentArtwork.getImageUri(), e);
            destFile.delete();
            EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(false, true));
            scheduleRetryArtworkDownload();
            return;
        }

        cleanupCache(selectedSource);

        EventBus.getDefault().postSticky(new ArtworkLoadingStateChangedEvent(false, false));
        EventBus.getDefault().post(new CurrentArtworkDownloadedEvent());
    }

    public File getArtworkCacheFile(ComponentName source, Artwork artwork) {
        File cacheRootForSource = getCacheRootForSource(source);
        if (cacheRootForSource == null) {
            LOGW(TAG, "Empty source cache root.");
            return null;
        }

        if (artwork == null) {
            LOGW(TAG, "Empty artwork info.");
            return null;
        }

        Uri uri = artwork.getImageUri();
        if (uri == null) {
            LOGW(TAG, "Empty artwork image.");
            return null;
        }

        return new File(cacheRootForSource, IOUtil.getCacheFilenameForUri(uri));
    }

    private File getCacheRootForSource(ComponentName source) {
        if (source == null) {
            LOGW(TAG, "Empty source.");
            return null;
        }

        String sourceDirName = source.flattenToShortString().replace('/', '_').replace('$', '_');
        return new File(mArtCacheRoot, sourceDirName);
    }

    private void cleanupCache(ComponentName source) {
        // Ensure cache doesn't go over MAX_CACHE_SIZE
        File[] cacheFiles = getCacheRootForSource(source).listFiles();
        if (cacheFiles == null || cacheFiles.length < MAX_CACHE_SIZE) {
            return;
        }

        SortedSet<Pair<Long, File>> latestFiles = new TreeSet<>(
                new Comparator<Pair<Long, File>>() {
                    @Override
                    public int compare(Pair<Long, File> p1, Pair<Long, File> p2) {
                        return -(p1.first < p2.first ? -1 : 1);
                    }
                });

        for (File file : cacheFiles) {
            latestFiles.add(new Pair<>(file.lastModified(), file));
        }

        // Remove everything but the first MAX_CACHE_SIZE files in the latest files set
        Iterator<Pair<Long, File>> it = latestFiles.iterator();
        int n = 0;
        while (it.hasNext()) {
            File cacheFile = it.next().second;
            if (++n <= MAX_CACHE_SIZE) {
                continue;
            }

            cacheFile.delete();
        }
    }

    private void cancelArtworkDownloadRetries() {
        AlarmManager am = (AlarmManager) mApplicationContext
                .getSystemService(Context.ALARM_SERVICE);
        am.cancel(TaskQueueService.getArtworkDownloadRetryPendingIntent(mApplicationContext));
    }

    private void scheduleRetryArtworkDownload() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mApplicationContext);
        int reloadAttempt = sp.getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0);
        sp.edit().putInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, reloadAttempt + 1).commit();

        AlarmManager am = (AlarmManager) mApplicationContext
                .getSystemService(Context.ALARM_SERVICE);
        long retryTimeMillis = SystemClock.elapsedRealtime() + (1 << reloadAttempt) * 2000;
        am.set(AlarmManager.ELAPSED_REALTIME, retryTimeMillis,
                TaskQueueService.getArtworkDownloadRetryPendingIntent(mApplicationContext));
    }

    public static Intent maybeRetryDownloadDueToGainedConnectivity(Context context) {
        return (PreferenceManager.getDefaultSharedPreferences(context)
                .getInt(PREF_ARTWORK_DOWNLOAD_ATTEMPT, 0) > 0)
                ? TaskQueueService.getDownloadCurrentArtworkIntent(context)
                : null;
    }
}
