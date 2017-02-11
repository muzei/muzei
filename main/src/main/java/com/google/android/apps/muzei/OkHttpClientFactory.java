package com.google.android.apps.muzei;

import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

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
     */
    public static OkHttpClient getNewOkHttpClient(boolean enableTLS) {
        OkHttpClient.Builder client = new OkHttpClient.Builder()
                .connectTimeout(DEFAULT_CONNECT_TIMEOUT, TimeUnit.SECONDS)
                .readTimeout(DEFAULT_READ_TIMEOUT, TimeUnit.SECONDS);
        if (enableTLS) {
            client = enableTls12(client);
        }
        return client.build();
    }

    /**
     * Creates a new OkHttpClient
     */
    public static OkHttpClient getNewOkHttpClient() {
        return getNewOkHttpClient(false);
    }

    /**
     * Creates a new OkHttpClient detecting if TLS needs to be enabled
     */
    public static OkHttpClient getNewOkHttpsSafeClient() {
        return getNewOkHttpClient(isTLSEnableNeeded());
    }

    /**
     * True if enabling TLS is needed on current device (SDK version >= 16 and < 22)
     */
    public static boolean isTLSEnableNeeded() {
        if (Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 22) {
            return true;
        }
        return false;
    }


    /**
     * Enable TLS on the OKHttp builder by setting a custom SocketFactory
     */
    private static OkHttpClient.Builder enableTls12(OkHttpClient.Builder client) {
        Log.i(TAG, "Enabling HTTPS compatibility mode");
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

            SSLContext sc = SSLContext.getInstance("TLSv1.2");
            sc.init(null, null, null);
            client.sslSocketFactory(new Tls12SocketFactory(sc.getSocketFactory()), trustManager);

            ConnectionSpec cs = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build();

            List<ConnectionSpec> specs = new ArrayList<>();
            specs.add(cs);
            specs.add(ConnectionSpec.COMPATIBLE_TLS);
            specs.add(ConnectionSpec.CLEARTEXT);

            client.connectionSpecs(specs);
        } catch (Exception exc) {
            Log.e(TAG, "Error while setting TLS 1.2", exc);
        }

        return client;
    }

    /**
     * Socket factory enabling TLS
     */
    private static class Tls12SocketFactory extends SSLSocketFactory {
        private static final String[] TLS = {"TLSv1.2"};

        final SSLSocketFactory delegate;

        public Tls12SocketFactory(SSLSocketFactory base) {
            this.delegate = base;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return delegate.getDefaultCipherSuites();
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return delegate.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return enableTLS(delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException, UnknownHostException {
            return enableTLS(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException, UnknownHostException {
            return enableTLS(delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return enableTLS(delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
            return enableTLS(delegate.createSocket(address, port, localAddress, localPort));
        }

        private Socket enableTLS(Socket s) {
            if (s instanceof SSLSocket) {
                ((SSLSocket) s).setEnabledProtocols(TLS);
            }
            return s;
        }

    }
}
