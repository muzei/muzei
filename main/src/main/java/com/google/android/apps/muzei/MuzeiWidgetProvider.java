package com.google.android.apps.muzei;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

import com.google.android.apps.muzei.api.MuzeiArtSource;

import net.nurik.roman.muzei.R;

public class MuzeiWidgetProvider extends AppWidgetProvider {
    private static final String ACTION_LAUNCHER_CLICKED = "MuzeiWidgetProvider.launcherButtonClicked";
    private static final String ACTION_NEXT_CLICKED = "MuzeiWidgetProvider.nextButtonClicked";

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        RemoteViews remoteViews = new RemoteViews(context.getPackageName(), R.layout.widget);
        ComponentName watchWidget = new ComponentName(context, MuzeiWidgetProvider.class);

        remoteViews.setOnClickPendingIntent(R.id.widget_launcher, getPendingIntent(context, ACTION_LAUNCHER_CLICKED));
        remoteViews.setOnClickPendingIntent(R.id.widget_next, getPendingIntent(context, ACTION_NEXT_CLICKED));

        appWidgetManager.updateAppWidget(watchWidget, remoteViews);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);

        if (ACTION_NEXT_CLICKED.equals(intent.getAction())) {
            // Request next artwork
            SourceManager.getInstance(context).sendAction(MuzeiArtSource.BUILTIN_COMMAND_ID_NEXT_ARTWORK);
        }/* else if (ACTION_LAUNCHER_CLICKED.equals(intent.getAction())) {
             Launch activity
             Intent launcher = new Intent(context, getClass());
             launcher.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
             context.startActivity(launcher);
        }*/
    }

    /**
     * Create Pending Intent to receive actions from widget
     *
     * @param context
     * @param action
     */
    private PendingIntent getPendingIntent(Context context, String action) {
        Intent intent = new Intent(context, getClass());
        intent.setAction(action);
        return PendingIntent.getBroadcast(context, 0, intent, 0);
    }
}