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

package com.google.android.apps.muzei.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.PowerManager;
import android.util.Log;

/**
 * A convenience subclass of {@link MuzeiArtSource} that's specifically designed for use by
 * art sources that fetch artwork metadata remotely. Sources that publish remote images but who
 * publish {@link Artwork} objects based on local data shouldnt need to use this subclass.
 *
 * <p> The only required method is {@link #onTryUpdate(int)}, which replaces the normal
 * {@link #onUpdate(int)} callback method.
 *
 * <p> This class automatically does the following in {@link #onUpdate(int)}:
 *
 * <ul>
 * <li>Check that the device is connected to the network. If not, schedule a retry using
 * the exponential backoff method and retry when the network becomes available.</li>
 * <li>Acquire a wakelock (held for a maximum of 30 seconds) and release it when
 * {@link #onTryUpdate(int)} finishes.</li>
 * <li>Automatically schedule retries using the exponential backoff method when
 * {@link #onTryUpdate(int)} throws a {@link RetryException}.</li>
 * </ul>
 *
 * <p> Applications with sources based on {@link RemoteMuzeiArtSource} must add the following
 * permissions:
 *
 * <pre class="prettyprint">
 * &lt;uses-permission android:name="android.permission.INTERNET" /&gt;
 * &lt;uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" /&gt;
 * &lt;uses-permission android:name="android.permission.WAKE_LOCK" /&gt;
 * </pre>
 */
public abstract class RemoteMuzeiArtSource extends MuzeiArtSource {
    private static final String TAG = "MuzeiArtSource";

    private static final int FETCH_WAKELOCK_TIMEOUT_MILLIS = 30 * 1000;
    private static final int INITIAL_RETRY_DELAY_MILLIS = 10 * 1000;

    private static final String PREF_RETRY_ATTEMPT = "retry_attempt";

    private String mName;

    /**
     * Remember to call this constructor from an empty constructor!
     */
    public RemoteMuzeiArtSource(String name) {
        super(name);
        mName = name;
    }

    /**
     * Subclasses should implement this method (instead of {@link #onUpdate(int)}) and attempt an
     * update, throwing a {@link RetryException} in case of a retryable error such as an HTTP
     * 500-level server error or a read error.
     */
    protected abstract void onTryUpdate(int reason) throws RetryException;

    /**
     * Subclasses of {@link RemoteMuzeiArtSource} should implement {@link #onTryUpdate(int)}
     * instead of this method.
     */
    @Override
    protected void onUpdate(int reason) {
        PowerManager pwm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock lock = pwm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, mName);
        lock.acquire(FETCH_WAKELOCK_TIMEOUT_MILLIS);

        SharedPreferences sp = getSharedPreferences();

        try {
            NetworkInfo ni = ((ConnectivityManager) getSystemService(
                    Context.CONNECTIVITY_SERVICE)).getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                Log.d(TAG, "No network connection; not attempting to fetch update, id=" + mName);
                throw new RetryException();
            }

            // In anticipation of update success, reset update attempt
            // Any alarms will be cleared before onUpdate is called
            sp.edit().remove(PREF_RETRY_ATTEMPT).apply();
            setWantsNetworkAvailable(false);

            // Attempt an update
            onTryUpdate(reason);

        } catch (RetryException e) {
            Log.w(TAG, "Error fetching, scheduling retry, id=" + mName);

            // Schedule retry with exponential backoff, starting with INITIAL_RETRY... seconds later
            int retryAttempt = sp.getInt(PREF_RETRY_ATTEMPT, 0);
            scheduleUpdate(
                    System.currentTimeMillis() + (INITIAL_RETRY_DELAY_MILLIS << retryAttempt));
            sp.edit().putInt(PREF_RETRY_ATTEMPT, retryAttempt + 1).apply();
            setWantsNetworkAvailable(true);

        } finally {
            if (lock.isHeld()) {
                lock.release();
            }
        }
    }

    @Override
    protected void onDisabled() {
        super.onDisabled();
        getSharedPreferences().edit().remove(PREF_RETRY_ATTEMPT).commit();
        setWantsNetworkAvailable(false);
    }

    @Override
    protected void onNetworkAvailable() {
        super.onNetworkAvailable();
        if (getSharedPreferences().getInt(PREF_RETRY_ATTEMPT, 0) > 0) {
            // This is a retry; attempt an update.
            onUpdate(UPDATE_REASON_OTHER);
        }
    }

    /**
     * An exception indicating that the {@link RemoteMuzeiArtSource} would like to retry
     * a failed update attempt.
     */
    public static class RetryException extends Exception {
        public RetryException() {
        }

        public RetryException(Throwable cause) {
            super(cause);
        }
    }
}
