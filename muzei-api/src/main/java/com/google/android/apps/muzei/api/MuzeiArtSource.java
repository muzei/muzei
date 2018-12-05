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

import android.app.Activity;
import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.apps.muzei.api.internal.SourceState;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.CallSuper;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_HANDLE_COMMAND;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_NETWORK_AVAILABLE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_PUBLISH_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.ACTION_SUBSCRIBE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_COMMAND_ID;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SCHEDULED;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_STATE;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_SUBSCRIBER_COMPONENT;
import static com.google.android.apps.muzei.api.internal.ProtocolConstants.EXTRA_TOKEN;

/**
 * Base class for a Muzei Live Wallpaper artwork source. Art sources are a way for other apps to
 * feed wallpapers (called {@linkplain Artwork artworks}) to the Muzei Live Wallpaper. Art sources
 * are specialized {@link IntentService}-like classes.
 *
 * <p> Only one source can be selected at a time. When the user chooses a source, the Muzei app
 * <em>subscribes</em> to the source for updates. When a different source is chosen, Muzei
 * <em>unsubscribes</em> from the original source.
 *
 * <p> The API is designed such that other applications besides Muzei Live Wallpaper can also
 * subscribe to updates from an artwork source.
 *
 * <h3>Subclassing {@link MuzeiArtSource}</h3>
 *
 * Subclasses must implement at least the {@link #onUpdate(int) onUpdate} callback method, which
 * may be called on a number of occasions, such as when an app subscribes to the source and the
 * source hasn't yet {@linkplain #publishArtwork(Artwork) published an artwork}, or when a
 * {@linkplain #scheduleUpdate(long) scheduled update} occurs.
 *
 * <p> To publish an artwork, call {@link #publishArtwork(Artwork)}, either from
 * the {@link #onUpdate(int) onUpdate} callback method, or elsewhere in the source's code. Any and
 * all subscribers will then immediately receive an update with the new artwork information. Under
 * the hood, this is all done with {@linkplain Context#startService(Intent) service intents}.
 *
 * <h3>Registering your source</h3>
 *
 * A source is simply a service that Muzei and other apps interact with via
 * {@linkplain Context#startService(Intent) service intents}. Subclasses of this base
 * {@link MuzeiArtSource} class should thus be declared as <code>&lt;service&gt;</code>
 * components in the application's <code>AndroidManifest.xml</code> file.
 *
 * <p> The Muzei app and other potential subscribers discover available sources using Android's
 * {@link Intent} mechanism. Ensure that your <code>service</code> definition includes an
 * <code>&lt;intent-filter&gt;</code> with an action of {@link #ACTION_MUZEI_ART_SOURCE}.
 *
 * <p> Muzei uses the drawable indicated by the source's <code>android:icon</code> attribute
 * in the <code>&lt;service&gt;</code> element to represent the source in the user interface.
 * The icon should be completely flat and contain padding, as Muzei will apply some additional
 * styling to present the icon.
 *
 * <p> Lastly, there are a few <code>&lt;meta-data&gt;</code> elements that you should add to your
 * service definition:
 *
 * <ul>
 * <li><code>color</code> (optional): should be a hex value representing your app or source's
 * brand, for example '#ace5cc'. Note that Muzei may adjust the color (specifically make it
 * brighter) to better fit in the user interface, so you should use pastel colors when
 * possible.</li>
 * <li><code>settingsActivity</code> (optional): if present, should be the qualified
 * component name for a configuration activity in the source's package that Muzei can offer
 * to the user for customizing the extension. This activity must be exported.</li>
 * <li><code>setupActivity</code> (optional): if present, should be the qualified
 * component name for an initial setup activity that must be ran before the source can be
 * activated. It will be started with {@link Activity#startActivityForResult} and must return
 * {@link Activity#RESULT_OK} for the source to be activated. This activity must be exported.</li>
 * <li><code>replacement</code> (optional): if present, should be the qualified
 * component name of a {@link com.google.android.apps.muzei.api.provider.MuzeiArtProvider}
 * that fully replaces this MuzeiArtSource. This informs Muzei that users should
 * automatically be migrated to the MuzeiArtProvider if they had the MuzeiArtSource
 * selected previously. It will also cause this MuzeiArtSource to be hidden
 * from the Muzei UI.</li>
 * </ul>
 *
 * <h3>Example</h3>
 *
 * Below is an example source declaration in the manifest:
 *
 * <pre class="prettyprint">
 * &lt;service android:name=".ExampleArtSource"
 *     android:icon="@drawable/ic_source_example"
 *     android:label="@string/source_title"
 *     android:description="@string/source_description"&gt;
 *     &lt;intent-filter&gt;
 *         &lt;action android:name="com.google.android.apps.muzei.api.MuzeiArtSource" /&gt;
 *     &lt;/intent-filter&gt;
 *     &lt;meta-data android:name="color" android:value="#ace5cc" /&gt;
 *     &lt;!-- A settings activity is optional --&gt;
 *     &lt;meta-data android:name="settingsActivity"
 *         android:value=".ExampleSettingsActivity" /&gt;
 * &lt;/service&gt;
 * </pre>
 *
 * If a <code>settingsActivity</code> meta-data element is present, an activity with the given
 * component name should be defined and exported in the application's manifest as well. Muzei
 * will set the {@link #EXTRA_FROM_MUZEI_SETTINGS} extra to true in the launch intent for this
 * activity. An example is shown below:
 *
 * <pre class="prettyprint">
 * &lt;activity android:name=".ExampleSettingsActivity"
 *     android:label="@string/title_settings"
 *     android:exported="true" /&gt;
 * </pre>
 *
 * Finally, below is a simple example {@link MuzeiArtSource} subclass that publishes a single,
 * static artwork:
 *
 * <pre class="prettyprint">
 * public class ExampleArtSource extends MuzeiArtSource {
 *     protected void onUpdate(int reason) {
 *         publishArtwork(new Artwork.Builder()
 *                 .imageUri(Uri.parse("http://example.com/image.jpg"))
 *                 .title("Example image")
 *                 .byline("Unknown person, c. 1980")
 *                 .viewIntent(new Intent(Intent.ACTION_VIEW,
 *                         Uri.parse("http://example.com/imagedetails.html")))
 *                 .build());
 *     }
 * }
 * </pre>
 *
 * <h3>Additional lifecycle methods</h3>
 *
 * There are a number of lifecycle methods that you can override for additional control. They
 * occur in the following order:
 *
 * <ol>
 * <li>{@link #onEnabled()}, called when the first subscriber is added.</li>
 * <li>{@link #onSubscriberAdded(ComponentName)}, called when a new subscriber is added.</li>
 * <li>{@link #onSubscriberRemoved(ComponentName)}, called when a subscriber is removed.</li>
 * <li>{@link #onDisabled()}, called when the last subscriber unsubscribes.</li>
 * </ol>
 *
 * In most cases, if the only subscriber is the Muzei Live Wallpaper app, these four methods
 * will be called as the user switches between your source and other available sources.
 *
 * <h3>Additional notes</h3>
 *
 * <p> To schedule an update for a future time, call {@link #scheduleUpdate(long)}. Cancel any
 * scheduled updates using {@link #unscheduleUpdate()}.
 *
 * <p> Sources can also expose additional user-facing commands (such as 'Next artwork' or 'Share
 * artwork') using the {@link #setUserCommands(UserCommand...)} method, and clear available actions
 * using {@link #removeAllUserCommands()}. To handle custom commands, override the
 * {@link #onCustomCommand(int)} callback method.
 *
 * <p> Sources can provide a dynamic description of the current configuration (e.g.
 * 'Popular photos tagged "landscape"'), by using the {@link #setDescription(String)}. If no
 * description is provided, the <code>android:description</code> element of the source's service
 * element in the manifest will be used.
 *
 * @see RemoteMuzeiArtSource
 */
public abstract class MuzeiArtSource extends Service {
    private static final String TAG = "MuzeiArtSource";

    /**
     * The {@link Intent} action representing a Muzei art source. This service should
     * declare an <code>&lt;intent-filter&gt;</code> for this action in order to register with
     * Muzei.
     */
    public static final String ACTION_MUZEI_ART_SOURCE
            = "com.google.android.apps.muzei.api.MuzeiArtSource";

    /**
     * Boolean extra that will be set to true when Muzei starts source settings activities.
     * Check for this extra in your settings activity if you need to adjust your UI depending on
     * whether or not the user came from Muzei's settings screen.
     */
    public static final String EXTRA_FROM_MUZEI_SETTINGS
            = "com.google.android.apps.muzei.api.extra.FROM_MUZEI_SETTINGS";

    private static final int FIRST_BUILTIN_COMMAND_ID = 1000;

    /**
     * The command ID for the built-in "next artwork" command. When this command is clicked,
     * {@link #onUpdate(int)} will be called with {@link #UPDATE_REASON_USER_NEXT}
     *
     * @see #setUserCommands(UserCommand...)
     */
    public static final int BUILTIN_COMMAND_ID_NEXT_ARTWORK = FIRST_BUILTIN_COMMAND_ID + 1;

    /**
     * The largest command ID that can be used for custom commands.
     *
     * @see #setUserCommands(UserCommand...)
     */
    protected static final int MAX_CUSTOM_COMMAND_ID = FIRST_BUILTIN_COMMAND_ID - 1;

    /**
     * The set of valid update reasons sent to {@link #onUpdate}.
     *
     * @see #UPDATE_REASON_OTHER
     * @see #UPDATE_REASON_INITIAL
     * @see #UPDATE_REASON_USER_NEXT
     * @see #UPDATE_REASON_SCHEDULED
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({UPDATE_REASON_OTHER, UPDATE_REASON_INITIAL, UPDATE_REASON_USER_NEXT, UPDATE_REASON_SCHEDULED})
    public @interface UpdateReason {}

    /**
     * Indicates that {@link #onUpdate(int)} was triggered for some reason not represented by
     * another known reason constant.
     */
    public static final int UPDATE_REASON_OTHER = 0;

    /**
     * Indicates that {@link #onUpdate(int)} was triggered because this source has not yet
     * published an artwork and the first subscriber has subscribed (e.g. the user has chosen
     * this source).
     */
    public static final int UPDATE_REASON_INITIAL = 1;

    /**
     * Indicates that {@link #onUpdate(int)} was triggered because the user manually requested
     * the next artwork. This should only be sent when {@link #BUILTIN_COMMAND_ID_NEXT_ARTWORK}
     * is an {@linkplain #setUserCommands(UserCommand...) available user command}.
     */
    public static final int UPDATE_REASON_USER_NEXT = 2;

    /**
     * Indicates that {@link #onUpdate(int)} was triggered because a
     * {@linkplain #scheduleUpdate(long) scheduled update} has been triggered.
     */
    public static final int UPDATE_REASON_SCHEDULED = 3;

    private static final String PREF_STATE = "state";
    private static final String PREF_SUBSCRIPTIONS = "subscriptions";
    private static final String PREF_SCHEDULED_UPDATE_TIME_MILLIS = "scheduled_update_time_millis";

    private static final String URI_SCHEME_COMMAND = "muzeicommand";

    private SharedPreferences mSharedPrefs;

    private Map<ComponentName, String> mSubscriptions;
    private SourceState mCurrentState;

    private final Runnable mPublishStateRunnable = new Runnable() {
        @Override
        public void run() {
            publishCurrentState();
            saveState();
        }
    };

    // From IntentService
    protected String mName;
    private boolean mRedelivery;
    private volatile Looper mServiceLooper;
    private volatile ServiceHandler mServiceHandler;

    private final class ServiceHandler extends Handler {
        ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            onHandleIntent((Intent) msg.obj);
            stopSelf(msg.arg1);
        }
    }

    /**
     * Remember to call this constructor from an empty constructor!
     *
     * @param name Should be an ID-style name for your source, usually just the class name. This is
     *             not user-visible and is only used for {@linkplain #getSharedPreferences()
     *             storing preferences} and in system log output.
     */
    public MuzeiArtSource(@NonNull String name) {
        super();
        mName = name;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        HandlerThread thread = new HandlerThread("IntentService[" + mName + "]");
        thread.start();
        mServiceLooper = thread.getLooper();
        mServiceHandler = new ServiceHandler(mServiceLooper);

        mSharedPrefs = getSharedPreferences();
        loadSubscriptions();
        loadState();
    }

    /**
     * You should not override this method for your MuzeiArtSource. Instead,
     * override {@link #onUpdate}, which Muzei calls when the MuzeiArtSource
     * receives an update request.
     *
     * @see android.app.IntentService#onStartCommand
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        Message msg = mServiceHandler.obtainMessage();
        msg.arg1 = startId;
        msg.obj = intent;
        mServiceHandler.sendMessage(msg);
        return mRedelivery ? START_REDELIVER_INTENT : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mServiceLooper.quitSafely();
        } else {
            // Risk losing some messages, but we're being destroyed anyways so...
            mServiceLooper.quit();
        }
    }

    /**
     * Sets intent redelivery preferences.  Usually called from the constructor
     * with your preferred semantics.
     *
     * <p>If enabled is true,
     * {@link #onStartCommand(Intent, int, int)} will return
     * {@link Service#START_REDELIVER_INTENT}, so if this process dies before
     * {@link #onHandleIntent(Intent)} returns, the process will be restarted
     * and the intent redelivered.  If multiple Intents have been sent, only
     * the most recent one is guaranteed to be redelivered.
     *
     * <p>If enabled is false (the default),
     * {@link #onStartCommand(Intent, int, int)} will return
     * {@link Service#START_NOT_STICKY}, and if the process dies, the Intent
     * dies along with it.
     *
     * @param enabled if you want the Intent to be redelivered if the process dies.
     *
     * @see IntentService#setIntentRedelivery
     */
    public void setIntentRedelivery(boolean enabled) {
        mRedelivery = enabled;
    }

    /**
     * Method called before a new subscriber is added that determines whether the subscription is
     * allowed or not. The default behavior is to allow all subscriptions.
     *
     * @param subscriber the subscriber that wants to be added.
     *
     * @return true if the subscription should be allowed, false if it should be denied.
     */
    protected boolean onAllowSubscription(@NonNull ComponentName subscriber) {
        return true;
    }

    /**
     * Lifecycle method called when a new subscriber is added. Sources generally don't need to
     * override this. For more details on the source lifecycle, see the discussion in the
     * {@link MuzeiArtSource} reference.
     *
     * @param subscriber the subscriber that was just added.
     */
    protected void onSubscriberAdded(@NonNull ComponentName subscriber) {
    }

    /**
     * Lifecycle method called when a subscriber is removed. Sources generally don't need to
     * override this. For more details on the source lifecycle, see the discussion in the
     * {@link MuzeiArtSource} reference.
     *
     * @param subscriber the subscriber that was just removed.
     */
    protected void onSubscriberRemoved(@NonNull ComponentName subscriber) {
    }

    /**
     * Lifecycle method called when the first subscriber is added. This will be called before
     * {@link #onSubscriberAdded(ComponentName)}. Sources generally don't need to override this.
     * For more details on the source lifecycle, see the discussion in the {@link MuzeiArtSource}
     * reference.
     */
    protected void onEnabled() {
    }

    /**
     * Lifecycle method called when the last subscriber is removed. This will be called after
     * {@link #onSubscriberRemoved(ComponentName)}. Sources generally don't need to override this.
     * For more details on the source lifecycle, see the discussion in the {@link MuzeiArtSource}
     * reference.
     */
    protected void onDisabled() {
    }

    /**
     * Called on occasions where the source should probably publish an artwork update.
     * Implementations can choose to do nothing, or more commonly, publish an artwork update using
     * {@link #publishArtwork(Artwork)}. Sources can also choose to update metadata here such as
     * {@link #setDescription(String)} or {@link #setUserCommands(UserCommand...)}.
     *
     * <p> Note that {@link #publishArtwork(Artwork)} can be called outside of this callback method.
     * This is simply the most common point at which you'll want to publish an update.
     *
     * @param reason The reason for the update. See {@link UpdateReason} and the related
     *               constants for more details.
     */
    protected abstract void onUpdate(@UpdateReason int reason);

    /**
     * Callback method indicating that the user has selected a custom command.
     *
     * @param id the ID of the command the user has chosen.
     *
     * @see #setUserCommands(UserCommand...)
     */
    protected void onCustomCommand(int id) {
    }

    /**
     * Convenience callback method indicating that a network connection is now available. This
     * will only be called if {@link #setWantsNetworkAvailable(boolean)} was last called with
     * <code>true</code>.
     */
    protected void onNetworkAvailable() {
    }

    /**
     * Publishes the provided {@link Artwork} object. This will be sent to all current subscribers
     * and to all future subscribers, until a new artwork is published.
     *
     * @param artwork the artwork to publish.
     */
    protected final void publishArtwork(@NonNull Artwork artwork) {
        artwork.setComponentName(new ComponentName(this, getClass()));
        mCurrentState.setCurrentArtwork(artwork);
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Sets the current source description of the current configuration. For example, 'Popular photos
     * tagged "landscape"'). If no description is provided, the <code>android:description</code>
     * element of the source's service element in the manifest will be used.
     *
     * @param description the new description to be shown when the source is selected.
     */
    protected final void setDescription(String description) {
        mCurrentState.setDescription(description);
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Sets the list of available user-visible commands for the source. Commands can be built-in,
     * such as {@link #BUILTIN_COMMAND_ID_NEXT_ARTWORK}, or custom-defined. Custom commands must
     * have identifiers below {@link #MAX_CUSTOM_COMMAND_ID}.
     *
     * <p> If you're only using built-in commands, {@link #setUserCommands(int...)} is preferred.
     *
     * @param commands the new set of user-visible commands the source supports.
     *
     * @see #BUILTIN_COMMAND_ID_NEXT_ARTWORK
     * @see #MAX_CUSTOM_COMMAND_ID
     */
    protected final void setUserCommands(UserCommand... commands) {
        mCurrentState.setUserCommands(Arrays.asList(commands));
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Sets the list of available user-visible commands for the source. Commands can be built-in,
     * such as {@link #BUILTIN_COMMAND_ID_NEXT_ARTWORK}, or custom-defined. Custom commands must
     * have identifiers below {@link #MAX_CUSTOM_COMMAND_ID}.
     *
     * @param commands the new set of user-visible commands the source supports.
     *
     * @see #BUILTIN_COMMAND_ID_NEXT_ARTWORK
     * @see #MAX_CUSTOM_COMMAND_ID
     */
    protected final void setUserCommands(List<UserCommand> commands) {
        mCurrentState.setUserCommands(commands);
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Sets the list of available user-visible commands for the source. Shorthand for
     * {@link #setUserCommands(UserCommand...)} using only the {@link UserCommand#UserCommand(int)}
     * constructor.
     *
     * @param commands the new set of user-visible commands the source supports.
     *
     * @see #BUILTIN_COMMAND_ID_NEXT_ARTWORK
     * @see #MAX_CUSTOM_COMMAND_ID
     */
    protected final void setUserCommands(int... commands) {
        mCurrentState.setUserCommands(commands);
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Clears the list of available user commands.
     *
     * @see #setUserCommands(UserCommand...)
     */
    protected final void removeAllUserCommands() {
        mCurrentState.setUserCommands((int[]) null);
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Indicates that the source is interested (or no longer interested) in getting notified via
     * {@link #onNetworkAvailable()} when a network connection becomes available.
     *
     * @param wantsNetworkAvailable Whether or not the source wants to be notified about network
     *                              availability.
     */
    protected final void setWantsNetworkAvailable(boolean wantsNetworkAvailable) {
        mCurrentState.setWantsNetworkAvailable(wantsNetworkAvailable);
        mServiceHandler.removeCallbacks(mPublishStateRunnable);
        mServiceHandler.post(mPublishStateRunnable);
    }

    /**
     * Returns the most recently {@linkplain #publishArtwork published} artwork.
     *
     * <p>This is based on the local saved state and may fall out of sync with Muzei if
     * either apps' data is cleared.
     *
     * @return the most recently {@linkplain #publishArtwork published} artwork, or null
     * if none has been published.
     */
    @Nullable
    protected final Artwork getCurrentArtwork() {
        return mCurrentState != null ? mCurrentState.getCurrentArtwork() : null;
    }

    /**
     * Schedules an update for some time in the future. Any previously scheduled updates will be
     * replaced. When the update time elapses, {@link #onUpdate(int)} will be called with
     * {@link #UPDATE_REASON_SCHEDULED}.
     *
     * <p> Note that this is persisted across device reboots, but only triggers after a subscriber
     * subscribes to the source after reboot. The Muzei Live Wallpaper will re-subscribe to sources
     * after reboot if it's the active wallpaper.
     *
     * @param scheduledUpdateTimeMillis The absolute scheduled update time, based on {@link
     *                                  System#currentTimeMillis()}. This value must be after
     *                                  the current time.
     */
    protected final void scheduleUpdate(long scheduledUpdateTimeMillis) {
        getSharedPreferences().edit()
                .putLong(PREF_SCHEDULED_UPDATE_TIME_MILLIS, scheduledUpdateTimeMillis).apply();
        setUpdateAlarm(scheduledUpdateTimeMillis);
    }

    /**
     * Cancels any {@linkplain #scheduleUpdate(long) previously scheduled} updates.
     */
    protected final void unscheduleUpdate() {
        getSharedPreferences().edit().remove(PREF_SCHEDULED_UPDATE_TIME_MILLIS).apply();
        clearUpdateAlarm();
    }

    /**
     * Whether this MuzeiArtSource is enabled.
     *
     * @return if this source is enabled; that is, if there is at least one active subscriber.
     *
     * @see #onEnabled()
     * @see #onDisabled()
     */
    protected synchronized final boolean isEnabled() {
        return mSubscriptions.size() > 0;
    }

    /**
     * Convenience method for accessing preferences specific to the source (with the given name
     * within this package. The source name must be the one provided in the
     * {@link #MuzeiArtSource(String)} constructor. This static method is useful for exposing source
     * preferences to other application components such as the source settings activity.
     *
     * @param context    the context; can be an application context.
     * @param sourceName the source name, provided in the {@link #MuzeiArtSource(String)}
     *                   constructor.
     *
     * @return the {@link SharedPreferences} where the MuzeiArtSource associated with the
     * sourceName stores its state.
     */
    protected static SharedPreferences getSharedPreferences(Context context, @NonNull String sourceName) {
        return context.getSharedPreferences("muzeiartsource_" + sourceName, 0);
    }

    /**
     * Convenience method for accessing preferences specific to the source.
     *
     * @return the {@link SharedPreferences} where this MuzeiArtSource stores its state.
     *
     * @see #getSharedPreferences(android.content.Context, String)
     */
    protected final SharedPreferences getSharedPreferences() {
        return getSharedPreferences(this, mName);
    }

    /**
     * This method is invoked on the worker thread with a request to process.
     * Only one Intent is processed at a time, but the processing happens on a
     * worker thread that runs independently from other application logic.
     * So, if this code takes a long time, it will hold up other requests to
     * the same IntentService, but it will not hold up anything else.
     * When all requests have been handled, the IntentService stops itself,
     * so you should not call {@link #stopSelf}.
     *
     * @param intent The value passed to {@link
     *               android.content.Context#startService(Intent)}.
     *               This may be null if the service is being restarted after
     *               its process has gone away; see
     *               {@link android.app.Service#onStartCommand}
     *               for details.
     *
     * @see IntentService#onHandleIntent(Intent)
     */
    @CallSuper
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        // TODO: permissions?
        if (ACTION_SUBSCRIBE.equals(action)) {
            processSubscribe(
                    (ComponentName) intent.getParcelableExtra(EXTRA_SUBSCRIBER_COMPONENT),
                    intent.getStringExtra(EXTRA_TOKEN));

        } else if (ACTION_HANDLE_COMMAND.equals(action)) {
            int commandId = intent.getIntExtra(EXTRA_COMMAND_ID, 0);
            processHandleCommand(commandId, intent.getExtras());

        } else if (ACTION_NETWORK_AVAILABLE.equals(action)) {
            processNetworkAvailable();
        }
    }

    private synchronized void processSubscribe(ComponentName subscriber, String token) {
        if (subscriber == null) {
            Log.w(TAG, "No subscriber given.");
            return;
        }

        String oldToken = mSubscriptions.get(subscriber);
        if (TextUtils.isEmpty(token)) {
            if (oldToken == null) {
                return;
            }

            // Unsubscribing
            mSubscriptions.remove(subscriber);
            processAndDispatchSubscriberRemoved(subscriber);

        } else {
            // Subscribing
            if (!TextUtils.isEmpty(oldToken)) {
                // Was previously subscribed, treat this as a unsubscribe + subscribe
                mSubscriptions.remove(subscriber);
                processAndDispatchSubscriberRemoved(subscriber);
            }

            if (!onAllowSubscription(subscriber)) {
                return;
            }

            mSubscriptions.put(subscriber, token);
            processAndDispatchSubscriberAdded(subscriber);
        }

        saveSubscriptions();
    }

    private synchronized void processAndDispatchSubscriberAdded(ComponentName subscriber) {
        // Trigger callbacks
        boolean updateDueToSchedule = false;
        if (mSubscriptions.size() == 1) {
            onEnabled();

            // See if we should trigger or reschedule a previously scheduled update.
            long updateTimeMillis = mSharedPrefs.getLong(PREF_SCHEDULED_UPDATE_TIME_MILLIS, 0);
            if (updateTimeMillis > 0) {
                if (updateTimeMillis < System.currentTimeMillis()) {
                    // Alarm time passed, trigger update now
                    updateDueToSchedule = true;
                    unscheduleUpdate();
                    onUpdate(UPDATE_REASON_SCHEDULED);
                } else {
                    // Time in the future, schedule the update
                    setUpdateAlarm(updateTimeMillis);
                }
            }
        }

        onSubscriberAdded(subscriber);

        // If there's no artwork, trigger initial update
        if (!updateDueToSchedule
                && mSubscriptions.size() == 1
                && mCurrentState.getCurrentArtwork() == null) {
            onUpdate(UPDATE_REASON_INITIAL);
        }

        // Immediately publish current state to subscriber
        publishCurrentState(subscriber);
    }

    private synchronized void processAndDispatchSubscriberRemoved(ComponentName subscriber) {
        // Trigger callbacks
        onSubscriberRemoved(subscriber);
        if (mSubscriptions.size() == 0) {
            clearUpdateAlarm();
            onDisabled();
        }
    }

    private void processHandleCommand(int commandId, Bundle extras) {
        Log.d(TAG, "Received handle command intent, command ID: " + commandId + ", id=" + mName);
        if (commandId == BUILTIN_COMMAND_ID_NEXT_ARTWORK) {
            int reason = extras.getBoolean(EXTRA_SCHEDULED, false)
                    ? UPDATE_REASON_SCHEDULED
                    : UPDATE_REASON_USER_NEXT;
            if (reason == UPDATE_REASON_SCHEDULED) {
                unscheduleUpdate();
            }
            onUpdate(reason);
        } else {
            onCustomCommand(commandId);
        }
    }

    private void processNetworkAvailable() {
        onNetworkAvailable();
    }

    private void setUpdateAlarm(long nextTimeMillis) {
        if (!isEnabled()) {
            Log.w(TAG, "Source has no subscribers, not actually scheduling next update"
                    + ", id=" + mName);
            return;
        }

        if (nextTimeMillis < System.currentTimeMillis()) {
            Log.w(TAG, "Refusing to schedule next artwork in the past, id=" + mName);
            return;
        }

        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.set(AlarmManager.RTC, nextTimeMillis, getHandleNextCommandPendingIntent(this));
        Log.i(TAG, "Scheduling next artwork (source " + mName + ") at " + new Date(nextTimeMillis));
    }

    private void clearUpdateAlarm() {
        AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
        am.cancel(getHandleNextCommandPendingIntent(this));
    }

    private PendingIntent getHandleNextCommandPendingIntent(Context context) {
        return PendingIntent.getService(context, 0,
                new Intent(ACTION_HANDLE_COMMAND)
                        .setComponent(new ComponentName(context, getClass()))
                        .setData(Uri.fromParts(URI_SCHEME_COMMAND,
                                Integer.toString(BUILTIN_COMMAND_ID_NEXT_ARTWORK), null))
                        .putExtra(EXTRA_COMMAND_ID, BUILTIN_COMMAND_ID_NEXT_ARTWORK)
                        .putExtra(EXTRA_SCHEDULED, true),
                PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private synchronized void publishCurrentState() {
        for (ComponentName subscription : mSubscriptions.keySet()) {
            publishCurrentState(subscription);
        }
    }

    private synchronized void publishCurrentState(final ComponentName subscriber) {
        String token = mSubscriptions.get(subscriber);
        if (TextUtils.isEmpty(token)) {
            Log.w(TAG, "Not active, canceling update, id=" + mName);
            return;
        }

        // Publish update
        Intent intent = new Intent(ACTION_PUBLISH_STATE)
                .setComponent(subscriber)
                .putExtra(EXTRA_TOKEN, token)
                .putExtra(EXTRA_STATE, (mCurrentState != null) ? mCurrentState.toBundle() : null);
        try {
            ComponentName returnedSubscriber = startService(intent);
            if (returnedSubscriber == null) {
                Log.e(TAG, "Update wasn't published because subscriber no longer exists"
                        + ", id=" + mName);
                // Unsubscribe the now-defunct subscriber
                mServiceHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        processSubscribe(subscriber, null);
                    }
                });
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Couldn't publish update, id=" + mName, e);
        }
    }

    private synchronized void loadSubscriptions() {
        mSubscriptions = new HashMap<>();
        Set<String> serializedSubscriptions = mSharedPrefs.getStringSet(PREF_SUBSCRIPTIONS, null);
        if (serializedSubscriptions != null) {
            for (String serializedSubscription : serializedSubscriptions) {
                String[] arr = serializedSubscription.split("\\|", 2);
                ComponentName subscriber = ComponentName.unflattenFromString(arr[0]);
                String token = arr[1];
                mSubscriptions.put(subscriber, token);
            }
        }
    }

    private synchronized void saveSubscriptions() {
        Set<String> serializedSubscriptions = new HashSet<>();
        for (ComponentName subscriber : mSubscriptions.keySet()) {
            serializedSubscriptions.add(subscriber.flattenToShortString() + "|"
                    + mSubscriptions.get(subscriber));
        }
        mSharedPrefs.edit().putStringSet(PREF_SUBSCRIPTIONS, serializedSubscriptions).apply();
    }

    private void loadState() {
        String stateString = mSharedPrefs.getString(PREF_STATE, null);
        if (stateString != null) {
            try {
                mCurrentState = SourceState.fromJson((JSONObject)
                        new JSONTokener(stateString).nextValue());
            } catch (JSONException e) {
                Log.e(TAG, "Couldn't deserialize current state, id=" + mName, e);
            }
        } else {
            mCurrentState = new SourceState();
        }
    }

    private void saveState() {
        try {
            mSharedPrefs.edit().putString(PREF_STATE, mCurrentState.toJson().toString()).apply();
        } catch (JSONException e) {
            Log.e(TAG, "Couldn't serialize current state, id=" + mName, e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
