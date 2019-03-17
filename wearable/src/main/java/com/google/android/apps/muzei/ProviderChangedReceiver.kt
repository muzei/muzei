package com.google.android.apps.muzei

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.support.wearable.notifications.BridgingConfig
import android.support.wearable.notifications.BridgingManager
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.apps.muzei.room.MuzeiDatabase
import com.google.android.apps.muzei.sync.ProviderChangedWorker
import com.google.android.apps.muzei.sync.ProviderManager
import com.google.android.apps.muzei.util.goAsync
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import net.nurik.roman.muzei.BuildConfig

class ProviderChangedReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "ProviderChangedReceiver"

        /**
         * Call this when a persistent listener changes visibility
         */
        fun onVisibleChanged(context: Context) {
            GlobalScope.launch {
                updateBridging(context)
            }
        }

        /**
         * Observe the [Lifecycle] of this component to get callbacks for when this
         * changes visibility.
         */
        fun observeForVisibility(context: Context, lifecycleOwner: LifecycleOwner) {
            lifecycleOwner.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    onVisibleChanged(context)
                } else if (event == Lifecycle.Event.ON_STOP) {
                    onVisibleChanged(context)
                }
            })
        }

        suspend fun updateBridging(context: Context) {
            val provider = MuzeiDatabase.getInstance(context).providerDao()
                    .getCurrentProvider()
            val dataLayerSelected = provider?.authority == BuildConfig.DATA_LAYER_AUTHORITY
            // Either we have an active listener or a persistent listener
            val isActive = ProviderManager.getInstance(context).hasActiveObservers() ||
                    ProviderChangedWorker.hasPersistentListeners(context)
            // Only bridge notifications if we're not currently showing any wallpaper
            // on the watch or if we're showing the same wallpaper as the phone
            // (i.e., the DataLayerArtProvider is selected)
            val bridgeNotifications = !isActive || dataLayerSelected
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Bridging changed to $bridgeNotifications: isActive=$isActive, " +
                        "selected Provider=$provider")
            }
            // Use the applicationContext because BridgingManager binds to a Service
            // and that's not allowed from a BroadcastReceiver's Context
            BridgingManager.fromContext(context.applicationContext).setConfig(
                    BridgingConfig.Builder(context, bridgeNotifications).build()
            )
        }
    }
    override fun onReceive(context: Context, intent: Intent) {
        goAsync {
            updateBridging(context)
        }
    }
}