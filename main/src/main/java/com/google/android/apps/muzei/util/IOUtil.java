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

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class IOUtil {
    private static final int DEFAULT_READ_TIMEOUT = 30; // in seconds
    private static final int DEFAULT_CONNECT_TIMEOUT = 15; // in seconds

    public static InputStream openUri(Context context, Uri uri, String reqContentTypeSubstring)
            throws OpenUriException {

        if (uri == null) {
            throw new IllegalArgumentException("Uri cannot be empty");
        }

        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new OpenUriException(false, new IOException("Uri had no scheme"));
        }

        InputStream in = null;
        if ("content".equals(scheme)) {
            try {
                in = context.getContentResolver().openInputStream(uri);
            } catch (FileNotFoundException | SecurityException e) {
                throw new OpenUriException(false, e);
            }

        } else if ("file".equals(scheme)) {
            List<String> segments = uri.getPathSegments();
            if (segments != null && segments.size() > 1
                    && "android_asset".equals(segments.get(0))) {
                AssetManager assetManager = context.getAssets();
                StringBuilder assetPath = new StringBuilder();
                for (int i = 1; i < segments.size(); i++) {
                    if (i > 1) {
                        assetPath.append("/");
                    }
                    assetPath.append(segments.get(i));
                }
                try {
                    in = assetManager.open(assetPath.toString());
                } catch (IOException e) {
                    throw new OpenUriException(false, e);
                }
            } else {
                try {
                    in = new FileInputStream(new File(uri.getPath()));
                } catch (FileNotFoundException e) {
                    throw new OpenUriException(false, e);
                }
            }

        } else if ("http".equals(scheme) || "https".equals(scheme)) {
            OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(DEFAULT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
                    .readTimeout(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS)
                    .build();
            Request request;
            int responseCode = 0;
            String responseMessage = null;
            try {
                request = new Request.Builder().url(new URL(uri.toString())).build();
            } catch (MalformedURLException e) {
                throw new OpenUriException(false, e);
            }

            try {
                Response response = client.newCall(request).execute();
                responseCode = response.code();
                responseMessage = response.message();
                if (!(responseCode >= 200 && responseCode < 300)) {
                    throw new IOException("HTTP error response.");
                }
                if (reqContentTypeSubstring != null) {
                    String contentType = response.header("Content-Type");
                    if (contentType == null || !contentType.contains(reqContentTypeSubstring)) {
                        throw new IOException("HTTP content type '" + contentType
                                + "' didn't match '" + reqContentTypeSubstring + "'.");
                    }
                }
                in = response.body().byteStream();

            } catch (IOException e) {
                if (responseCode > 0) {
                    throw new OpenUriException(
                            500 <= responseCode && responseCode < 600,
                            responseMessage, e);
                } else {
                    throw new OpenUriException(false, e);
                }

            }
        }

        if (in == null) {
            throw new OpenUriException(false, "Null input stream for URI: " + uri, null);
        }

        return in;
    }

    public static class OpenUriException extends Exception {
        private boolean mRetryable;

        public OpenUriException(boolean retryable, String message, Throwable cause) {
            super(message, cause);
            mRetryable = retryable;
        }

        public OpenUriException(boolean retryable, Throwable cause) {
            super(cause);
            mRetryable = retryable;
        }

        public boolean isRetryable() {
            return mRetryable;
        }
    }
}
