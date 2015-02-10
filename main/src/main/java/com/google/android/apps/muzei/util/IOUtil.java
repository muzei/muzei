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
import android.os.Environment;
import android.support.v4.content.ContextCompat;
import android.support.v4.os.EnvironmentCompat;
import android.text.TextUtils;

import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.OkUrlFactory;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public class IOUtil {
    private static final int DEFAULT_READ_TIMEOUT = 30 * 1000; // 30s
    private static final int DEFAULT_CONNECT_TIMEOUT = 15 * 1000; // 15s

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
            OkHttpClient client = new OkHttpClient();
            HttpURLConnection conn = null;
            int responseCode = 0;
            String responseMessage = null;
            try {
                conn = new OkUrlFactory(client).open(new URL(uri.toString()));
            } catch (MalformedURLException e) {
                throw new OpenUriException(false, e);
            }

            try {
                conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
                conn.setReadTimeout(DEFAULT_READ_TIMEOUT);
                responseCode = conn.getResponseCode();
                responseMessage = conn.getResponseMessage();
                if (!(responseCode >= 200 && responseCode < 300)) {
                    throw new IOException("HTTP error response.");
                }
                if (reqContentTypeSubstring != null) {
                    String contentType = conn.getContentType();
                    if (contentType == null || !contentType.contains(reqContentTypeSubstring)) {
                        throw new IOException("HTTP content type '" + contentType
                                + "' didn't match '" + reqContentTypeSubstring + "'.");
                    }
                }
                in = conn.getInputStream();

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

        return in;
    }

    public static String getCacheFilenameForUri(Uri uri) {
        StringBuilder filename = new StringBuilder();
        filename.append(uri.getScheme()).append("_")
                .append(uri.getHost()).append("_");
        String encodedPath = uri.getEncodedPath();
        if (!TextUtils.isEmpty(encodedPath)) {
            int length = encodedPath.length();
            if (length > 60) {
                encodedPath = encodedPath.substring(length - 60);
            }
            encodedPath = encodedPath.replace('/', '_');
            filename.append(encodedPath).append("_");
        }
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(uri.toString().getBytes("UTF-8"));
            byte[] digest = md.digest();
            for (byte b : digest) {
                if ((0xff & b) < 0x10) {
                    filename.append("0").append(Integer.toHexString((0xFF & b)));
                } else {
                    filename.append(Integer.toHexString(0xFF & b));
                }
            }
        } catch (NoSuchAlgorithmException | UnsupportedEncodingException e) {
            filename.append(uri.toString().hashCode());
        }

        return filename.toString();
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

    public static JSONObject fetchJsonObject(String url) throws IOException, JSONException {
        return readJsonObject(fetchPlainText(new URL(url)));
    }

    public static JSONObject readJsonObject(String json) throws IOException, JSONException {
        JSONTokener tokener = new JSONTokener(json);
        Object val = tokener.nextValue();
        if (!(val instanceof JSONObject)) {
            throw new JSONException("Expected JSON object.");
        }
        return (JSONObject) val;
    }

    public static void readFullyWriteToFile(InputStream in, File file) throws IOException {
        readFullyWriteToOutputStream(in, new FileOutputStream(file));
    }

    public static void readFullyWriteToOutputStream(InputStream in, OutputStream out)
            throws IOException {
        try {
            byte[] buffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) > 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } finally {
            out.close();
        }
    }

    public static String readFullyPlainText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = in.read(buffer)) > 0) {
            out.write(buffer, 0, bytesRead);
        }
        return new String(out.toByteArray(), "UTF-8");
    }

    public static File getBestAvailableCacheRoot(Context context) {
        File[] roots = ContextCompat.getExternalCacheDirs(context);
        if (roots != null) {
            for (File root : roots) {
                if (root == null) {
                    continue;
                }

                if (Environment.MEDIA_MOUNTED.equals(EnvironmentCompat.getStorageState(root))) {
                    return root;
                }
            }
        }

        // Worst case, resort to internal storage
        return context.getCacheDir();
    }

    public static File getBestAvailableFilesRoot(Context context) {
        File[] roots = ContextCompat.getExternalFilesDirs(context, null);
        if (roots != null) {
            for (File root : roots) {
                if (root == null) {
                    continue;
                }

                if (Environment.MEDIA_MOUNTED.equals(EnvironmentCompat.getStorageState(root))) {
                    return root;
                }
            }
        }

        // Worst case, resort to internal storage
        return context.getFilesDir();
    }

    static String fetchPlainText(URL url) throws IOException {
        InputStream in = null;

        try {
            OkHttpClient client = new OkHttpClient();
            HttpURLConnection conn = new OkUrlFactory(client).open(url);
            conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT);
            conn.setReadTimeout(DEFAULT_READ_TIMEOUT);
            in = conn.getInputStream();
            return readFullyPlainText(in);

        } finally {
            if (in != null) {
                in.close();
            }
        }
    }
}
