package org.acf.mtracker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Brings the monitoring service back after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.serviceEnabled(context)) return;
        try {
            AlertService.start(context);
        } catch (Exception ignored) {
            AlertService.scheduleWatchdog(context);
        }
    }
}
