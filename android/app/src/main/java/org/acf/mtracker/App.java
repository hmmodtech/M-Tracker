package org.acf.mtracker;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Tracks whether any screen of the app is in front (needed to decide how
 *  the alert may be shown) and prepares the notification channels. */
public class App extends Application {

    /** true while one of our activities is visible. */
    public static volatile boolean foreground = false;

    private int started = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.ensureChannels(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                started++;
                foreground = started > 0;
            }
            @Override public void onActivityStopped(Activity a) {
                started--;
                if (started < 0) started = 0;
                foreground = started > 0;
            }
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityResumed(Activity a) { foreground = true; }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
