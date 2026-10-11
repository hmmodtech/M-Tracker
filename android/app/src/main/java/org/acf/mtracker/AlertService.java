package org.acf.mtracker;

import android.app.AlarmManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The heart of the app: keeps one long-poll request open against the server
 * (/api/stream) and turns every message the server hands back into a
 * notification — and, when the screen is locked, into a full-screen alert.
 */
public class AlertService extends Service {

    public static final String TAG = "MTracker";

    public static final String ACTION_START   = "org.acf.mtracker.action.START";
    public static final String ACTION_STOP    = "org.acf.mtracker.action.STOP";
    public static final String ACTION_PING    = "org.acf.mtracker.action.PING";
    public static final String ACTION_DISMISS = "org.acf.mtracker.action.DISMISS";
    public static final String ACTION_STATUS  = "org.acf.mtracker.action.STATUS";
    public static final String ACTION_NEWS    = "org.acf.mtracker.action.NEWS";
    public static final String EXTRA_STATUS   = "status";

    private static final long WATCHDOG_MS = 15 * 60 * 1000L;

    private volatile boolean running = false;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private BroadcastReceiver screenReceiver;

    // ── control helpers ───────────────────────────────────────────────
    public static void start(Context c) {
        Intent i = new Intent(c, AlertService.class).setAction(ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception e) {
            // Android 12+ can refuse a background start (no battery exemption
            // granted). The watchdog alarm will try again later.
            Log.w(TAG, "start refused: " + e);
            scheduleWatchdog(c);
        }
    }

    public static void stop(Context c) {
        c.startService(new Intent(c, AlertService.class).setAction(ACTION_STOP));
    }

    /** Safety net: if the system kills us, try again in a few minutes. */
    public static void scheduleWatchdog(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            Intent i = new Intent(c, AlertService.class).setAction(ACTION_PING);
            PendingIntent pi = PendingIntent.getService(c, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WATCHDOG_MS, WATCHDOG_MS, pi);
        } catch (Exception e) {
            Log.w(TAG, "watchdog: " + e);
        }
    }

    public static void cancelWatchdog(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            Intent i = new Intent(c, AlertService.class).setAction(ACTION_PING);
            PendingIntent pi = PendingIntent.getService(c, 7, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.cancel(pi);
        } catch (Exception ignored) {}
    }

    public static void scheduleQuickRestart(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            if (am == null) return;
            Intent i = new Intent(c, AlertService.class).setAction(ACTION_PING);
            PendingIntent pi = PendingIntent.getService(c, 8, i,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3000, pi);
        } catch (Exception ignored) {}
    }

    // ── lifecycle ─────────────────────────────────────────────────────
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Notifier.ensureChannels(this);
        screenReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                applyWakeLock();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenReceiver, f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_START;

        if (ACTION_DISMISS.equals(action)) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            int id = intent.getIntExtra("notif_id", -1);
            if (nm != null && id >= 0) nm.cancel(id);
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopEverything();
            return START_NOT_STICKY;
        }

        startForegroundCompat();
        if (!running) startWorker();
        scheduleWatchdog(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        releaseWakeLock();
        try { if (screenReceiver != null) unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        setStatus("stopped");
        if (Prefs.serviceEnabled(this)) {
            scheduleWatchdog(this);
            scheduleQuickRestart(this);   // came back on its own if we were killed
        }
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // user swiped the app out of "recent apps": keep watching
        if (Prefs.serviceEnabled(this)) scheduleQuickRestart(this);
        super.onTaskRemoved(rootIntent);
    }

    private void stopEverything() {
        running = false;
        if (worker != null) worker.interrupt();
        worker = null;
        releaseWakeLock();
        cancelWatchdog(this);
        setStatus("stopped");
        stopForeground(true);
        stopSelf();
    }

    private void startForegroundCompat() {
        Notification n = Notifier.serviceNotification(this);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifier.ID_SERVICE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(Notifier.ID_SERVICE, n);
        }
        setStatus("connecting");
    }

    // ── the polling loop ──────────────────────────────────────────────
    private void startWorker() {
        running = true;
        worker = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "mtracker-stream");
        worker.start();
    }

    private void loop() {
        int fails = 0;
        while (running) {
            try {
                long cursor = Prefs.cursor(this);
                String base  = Prefs.url(this);
                String token = Prefs.token(this);
                String cookie = Prefs.cookie(this);

                if (cursor < 0) {
                    // first run: start from "now", never replay old news
                    Http.Res r = Http.get(base + "api/stream?baseline=1&since=0",
                            cookie, token, 30000);
                    if (r.code != 200) throw new java.io.IOException("HTTP " + r.code);
                    JSONObject o = new JSONObject(r.body);
                    Prefs.setCursor(this, o.optLong("cursor", 0L));
                    fails = 0;
                    setStatus("connected");
                    applyWakeLock();
                    continue;
                }

                long t0 = SystemClock.elapsedRealtime();
                int wait = waitSeconds();
                String url = base + "api/stream?since=" + cursor
                        + "&scope=" + Prefs.scope(this) + "&wait=" + wait
                        + "&device=" + Prefs.deviceId(this);
                Http.Res r = Http.get(url, cookie, token, (wait + 30) * 1000);

                if (r.code == 401) {
                    setStatus("token");
                    sleep(60000);
                    continue;
                }
                if (r.code != 200) throw new java.io.IOException("HTTP " + r.code);

                JSONObject o = new JSONObject(r.body);
                Prefs.setCursor(this, o.optLong("cursor", cursor));
                JSONArray ev = o.optJSONArray("events");
                if (ev != null && ev.length() > 0) {
                    List<AlertItem> items = new ArrayList<>();
                    for (int i = 0; i < ev.length(); i++) items.add(AlertItem.fromJson(ev.getJSONObject(i)));
                    deliver(items);
                } else if (SystemClock.elapsedRealtime() - t0 < 1000) {
                    // server answered instantly with nothing (e.g. first run
                    // against an empty database) - do not spin
                    sleep(1000);
                }
                fails = 0;
                setStatus("connected");
            } catch (InterruptedException ie) {
                break;
            } catch (Exception e) {
                fails++;
                Log.w(TAG, "stream error (" + fails + "): " + e);
                setStatus(fails >= 3 ? "offline" : "connecting");
                try {
                    sleep(Math.min(60000L, 3000L * fails));
                } catch (InterruptedException ie) {
                    break;
                }
            }
            applyWakeLock();
        }
    }

    /** How long the server may hold the request open (also the max alert delay). */
    private int waitSeconds() {
        if (Prefs.batterySaver(this) && !isInteractive()) return 120;
        return 25;
    }

    // ── turning messages into alerts ──────────────────────────────────
    private void deliver(List<AlertItem> items) {
        boolean anyCritical = false;
        for (AlertItem it : items) if (it.critical) { anyCritical = true; break; }

        if (items.size() > 4) {
            // a burst: one summary + full screen for the newest important one
            AlertItem sample = items.get(items.size() - 1);
            Notifier.postSummary(this, items.size(), sample.body(), anyCritical);
            for (AlertItem it : items) {
                if (it.critical) { showAlert(it); return; }
            }
            return;
        }
        for (AlertItem it : items) showAlert(it);
    }

    private void showAlert(AlertItem it) {
        boolean locked = !isInteractive() || isKeyguardLocked();
        boolean wantTakeover = Prefs.takeover(this)
                && locked
                && (it.critical || Prefs.takeoverAll(this));

        boolean canLaunch = App.foreground || canDrawOverlays();
        boolean fullScreenIntent = wantTakeover;   // used when we are not allowed to launch ourselves

        Notifier.post(this, it, fullScreenIntent);

        if (wantTakeover && (canLaunch || fullScreenIntent)) {
            Alerts.push(it);
            if (canLaunch) {
                try {
                    Intent i = new Intent(this, AlertActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .putExtra("item", it.toJson());
                    startActivity(i);
                } catch (Exception e) {
                    Log.w(TAG, "takeover launch blocked: " + e);
                }
            }
        } else if (wantTakeover) {
            Log.i(TAG, "no overlay permission -> heads-up notification only");
        }
        // let the WebView feed refresh itself when the app is open
        Intent news = new Intent(ACTION_NEWS).setPackage(getPackageName());
        sendBroadcast(news);
    }

    // ── power / screen helpers ────────────────────────────────────────
    private boolean isInteractive() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private boolean isKeyguardLocked() {
        KeyguardManager km = getSystemService(KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private void applyWakeLock() {
        boolean want = Prefs.serviceEnabled(this)
                && !(Prefs.batterySaver(this) && !isInteractive());
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm == null) return;
            if (want) {
                if (wake == null) {
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mtracker:stream");
                    wake.setReferenceCounted(false);
                }
                if (!wake.isHeld()) wake.acquire();
            } else {
                releaseWakeLock();
            }
        } catch (Exception e) {
            Log.w(TAG, "wakelock: " + e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {}
    }

    private void setStatus(String s) {
        Prefs.setStatus(this, s);
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName()).putExtra(EXTRA_STATUS, s);
        sendBroadcast(i);
    }

    private void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
