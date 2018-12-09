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
package com.google.android.apps.muzei.sources;

import android.os.Build;
import android.util.Log;

import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import androidx.annotation.NonNull;
import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.TlsVersion;

/**
 * Factory for OkHttpClient, supports the creation of clients enabling TLS on devices where it's not enabled by default (mainly pre lollipop)
 */
public class OkHttpClientFactory {
    private static final String TAG = "OkHttpClientFactory";
    private static final int DEFAULT_READ_TIMEOUT = 30; // in seconds
    private static final int DEFAULT_CONNECT_TIMEOUT = 15; // in seconds

    /**
     * Creates an OkHttpClient optionally enabling TLS
     * @param enableTLS Whether TLS should be enabled
     * @return a valid OkHttpClient
     */
    @NonNull
    private static OkHttpClient getNewOkHttpClient(boolean enableTLS) {
        OkHttpClient.Builder client = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        if (enableTLS) {
            client = enableTls12(client);
        }
        return client.build();
    }

    /**
     * Creates a new OkHttpClient detecting if TLS needs to be enabled
     * @return a valid OkHttpClient
     */
    @NonNull
    public static OkHttpClient getNewOkHttpsSafeClient() {
        return getNewOkHttpClient(isTLSEnableNeeded());
    }

    /**
     * @return True if enabling TLS is needed on current device (SDK version &gt;= 16 and &lt; 22)
     */
    private static boolean isTLSEnableNeeded() {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP;
    }

    /**
     * Enable TLS on the OKHttp builder by setting a custom SocketFactory
     * @param client the client to enable TLS
     * @return an OkhttpClient.Builder with TLS enabled
     */
    private static OkHttpClient.Builder enableTls12(OkHttpClient.Builder client) {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init((KeyStore) null);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new IllegalStateException("Unexpected default trust managers:"
                        + Arrays.toString(trustManagers));
            }
            X509TrustManager trustManager = (X509TrustManager) trustManagers[0];
            client.sslSocketFactory(new TLSSocketFactory(), trustManager);
            ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2, TlsVersion.TLS_1_1)
                    .build();
            List<ConnectionSpec> specs = new ArrayList<>();
            specs.add(cs);
            specs.add(ConnectionSpec.COMPATIBLE_TLS);
            specs.add(ConnectionSpec.CLEARTEXT);
            client.connectionSpecs(specs);
        } catch (Exception exc) {
            Log.e(TAG, "Error while setting TLS", exc);
        }
        return client;
    }
}
