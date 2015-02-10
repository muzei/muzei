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

package com.google.android.apps.muzei.featuredart;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.RemoteMuzeiArtSource;
import com.google.android.apps.muzei.api.UserCommand;
import com.google.android.apps.muzei.util.IOUtil;
import com.google.android.apps.muzei.util.LogUtil;

import net.nurik.roman.muzei.BuildConfig;
import net.nurik.roman.muzei.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.TimeZone;

import static com.google.android.apps.muzei.util.LogUtil.LOGD;
import static com.google.android.apps.muzei.util.LogUtil.LOGE;
import static com.google.android.apps.muzei.util.LogUtil.LOGW;

public class FeaturedArtSource extends RemoteMuzeiArtSource {
    private static final String TAG = LogUtil.makeLogTag(FeaturedArtSource.class);
    private static final String SOURCE_NAME = "FeaturedArt";

    private static final String QUERY_URL = "http://muzeiapi.appspot.com/featured?cachebust=1";
    private static final Uri ARCHIVE_URI = Uri.parse("http://muzei.co/archive");

    private static final int COMMAND_ID_SHARE = 1;
    private static final int COMMAND_ID_VIEW_ARCHIVE = 2;
    private static final int COMMAND_ID_DEBUG_INFO = 51;

    private static final int MAX_JITTER_MILLIS = 20 * 60 * 1000;

    private static Random sRandom = new Random();

    private static final SimpleDateFormat sDateFormatTZ
            = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final SimpleDateFormat sDateFormatLocal
            = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
    static {
        sDateFormatTZ.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    public FeaturedArtSource() {
        super(SOURCE_NAME);
    }

    @Override
    protected void onUpdate(int reason) {
        List<UserCommand> commands = new ArrayList<>();
        if (reason == UPDATE_REASON_INITIAL) {
            // Show initial photo (starry night)
            publishArtwork(new Artwork.Builder()
                    .imageUri(Uri.parse("file:///android_asset/starrynight.jpg"))
                    .title("The Starry Night")
                    .token("initial")
                    .byline("Vincent van Gogh, 1889.\nMuzei shows a new painting every day.")
                    .viewIntent(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("http://www.wikipaintings.org/en/vincent-van-gogh/the-starry-night-1889")))
                    .build());
            commands.add(new UserCommand(BUILTIN_COMMAND_ID_NEXT_ARTWORK));
            // show the latest photo in 15 minutes
            scheduleUpdate(System.currentTimeMillis() + 15 * 60 * 1000);

        } else {
            // For everything but the initial update, defer to RemoteMuzeiArtSource
            super.onUpdate(reason);
        }

        commands.add(new UserCommand(COMMAND_ID_SHARE, getString(R.string.action_share_artwork)));
        commands.add(new UserCommand(COMMAND_ID_VIEW_ARCHIVE,
                getString(R.string.featuredart_source_action_view_archive)));
        if (BuildConfig.DEBUG) {
            commands.add(new UserCommand(COMMAND_ID_DEBUG_INFO, "Debug info"));
        }
        setUserCommands(commands);
    }

    @Override
    protected void onSubscriberAdded(ComponentName subscriber) {
        super.onSubscriberAdded(subscriber);
        Artwork currentArtwork = getCurrentArtwork();
        if (currentArtwork != null && !"initial".equals(currentArtwork.getToken())) {
            // TODO: is this really necessary?
            // When a subscriber is added, manually try a fetch, unless this is the
            // first update
            onUpdate(UPDATE_REASON_OTHER);
        }
    }

    @Override
    protected void onCustomCommand(int id) {
        super.onCustomCommand(id);
        if (COMMAND_ID_SHARE == id) {
            Artwork currentArtwork = getCurrentArtwork();
            if (currentArtwork == null) {
                LOGW(TAG, "No current artwork, can't share.");
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(FeaturedArtSource.this,
                                R.string.featuredart_source_error_no_artwork_to_share,
                                Toast.LENGTH_SHORT).show();
                    }
                });
                return;
            }

            String detailUrl = ("initial".equals(currentArtwork.getToken()))
                    ? "http://www.wikipaintings.org/en/vincent-van-gogh/the-starry-night-1889"
                    : currentArtwork.getViewIntent().getDataString();
            String artist = currentArtwork.getByline()
                    .replaceFirst("\\.\\s*($|\\n).*", "").trim();

            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(Intent.EXTRA_TEXT, "My Android wallpaper today is '"
                    + currentArtwork.getTitle().trim()
                    + "' by " + artist
                    + ". #MuzeiFeaturedArt\n\n"
                    + detailUrl);
            shareIntent = Intent.createChooser(shareIntent, "Share artwork");
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(shareIntent);

        } else if (COMMAND_ID_VIEW_ARCHIVE == id) {
            Intent viewArchiveIntent = new Intent(Intent.ACTION_VIEW, ARCHIVE_URI);
            viewArchiveIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            try {
                startActivity(viewArchiveIntent);
            } catch (ActivityNotFoundException ignored) {
            }

        } else if (COMMAND_ID_DEBUG_INFO == id) {
            long nextUpdateTimeMillis = getSharedPreferences()
                    .getLong("scheduled_update_time_millis", 0);
            final String nextUpdateTime;
            if (nextUpdateTimeMillis > 0) {
                Date d = new Date(nextUpdateTimeMillis);
                nextUpdateTime = SimpleDateFormat.getDateTimeInstance().format(d);
            } else {
                nextUpdateTime = "None";
            }

            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(FeaturedArtSource.this,
                            "Next update time: " + nextUpdateTime,
                            Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override
    protected void onTryUpdate(int reason) throws RetryException {
        Artwork currentArtwork = getCurrentArtwork();
        Artwork artwork;
        JSONObject jsonObject;
        try {
            jsonObject = IOUtil.fetchJsonObject(QUERY_URL);
            artwork = Artwork.fromJson(jsonObject);
        } catch (JSONException | IOException e) {
            LOGE(TAG, "Error reading JSON", e);
            throw new RetryException(e);
        }

        if (artwork != null && currentArtwork != null && artwork.getImageUri() != null &&
                artwork.getImageUri().equals(currentArtwork.getImageUri())) {
            LOGD(TAG, "Skipping update of same artwork.");
        } else {
            LOGD(TAG, "Publishing artwork update: " + artwork);
            if (artwork != null && jsonObject != null) {
                publishArtwork(artwork);
            }
        }

        Date nextTime = null;
        String nextTimeStr = jsonObject.optString("nextTime");
        if (!TextUtils.isEmpty(nextTimeStr)) {
            int len = nextTimeStr.length();
            if (len > 4 && nextTimeStr.charAt(len - 3) == ':') {
                nextTimeStr = nextTimeStr.substring(0, len - 3) + nextTimeStr.substring(len - 2);
            }
            try {
                nextTime = sDateFormatTZ.parse(nextTimeStr);
            } catch (ParseException e) {
                try {
                    sDateFormatLocal.setTimeZone(TimeZone.getDefault());
                    nextTime = sDateFormatLocal.parse(nextTimeStr);
                } catch (ParseException e2) {
                    LOGE(TAG, "Can't schedule update; "
                            + "invalid date format '" + nextTimeStr + "'", e2);
                }
            }
        }

        boolean scheduleFallback = true;
        if (nextTime != null) {
            // jitter by up to N milliseconds
            scheduleUpdate(nextTime.getTime() + sRandom.nextInt(MAX_JITTER_MILLIS));
            scheduleFallback = false;
        }

        if (scheduleFallback) {
            // No next time, default to checking in 12 hours
            scheduleUpdate(System.currentTimeMillis() + 12 * 60 * 60 * 1000);
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (BuildConfig.DEBUG && intent != null && "demoartwork".equals(intent.getAction())) {
            publishArtwork(new Artwork.Builder()
                    .imageUri(Uri.parse(intent.getStringExtra("image")))
                    .title(intent.getStringExtra("title"))
                    .token(intent.getStringExtra("image"))
                    .byline(intent.getStringExtra("byline"))
                    .viewIntent(new Intent(Intent.ACTION_VIEW,
                            Uri.parse(intent.getStringExtra("details"))))
                    .build());
            removeAllUserCommands();
        }

        super.onHandleIntent(intent);
    }
}
