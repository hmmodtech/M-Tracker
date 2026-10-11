package org.acf.mtracker;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** Tracks whether a screen of the app is in front (needed to decide how an
 *  alert may be shown), prepares the notification channels and makes sure a
 *  crash is never silent. */
public class App extends Application {

    /** true while one of our activities is visible. */
    public static volatile boolean foreground = false;

    private int started = 0;
    private static boolean crashHandlerInstalled = false;

    @Override
    public void onCreate() {
        super.onCreate();

        // Never let a startup problem kill the app before the user sees anything.
        try {
            Notifier.ensureChannels(this);
        } catch (Throwable t) {
            CrashLog.record(this, "Notifier.ensureChannels", t);
        }

        installCrashHandler();

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

    private void installCrashHandler() {
        if (crashHandlerInstalled) return;
        crashHandlerInstalled = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    CrashLog.record(App.this, "uncaught/" + thread.getName(), e);
                } catch (Throwable ignored) {}
                if (previous != null) previous.uncaughtException(thread, e);
            }
        });
    }
}
