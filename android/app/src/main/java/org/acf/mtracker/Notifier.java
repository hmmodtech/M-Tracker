package org.acf.mtracker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

/** Builds the notification channels and the alert notifications. */
public final class Notifier {

    public static final String CH_CRITICAL = "mtracker_critical";
    public static final String CH_GENERAL  = "mtracker_general";
    public static final String CH_SERVICE  = "mtracker_service";

    public static final int ID_SERVICE = 1001;

    /** Stable notification id per message, so the alert screen can clear it. */
    public static int notifId(AlertItem it) { return (int) (it.id % 100000L) + 5000; }

    private Notifier() {}

    // ── channels ──────────────────────────────────────────────────────
    /**
     * (Re)creates the three channels. Called at startup and whenever the
     * sound settings change: sound/vibration can be updated on an existing
     * channel, importance can not (the user owns that).
     */
    public static void ensureChannels(Context c) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;

        boolean sound = Prefs.sound(c);
        Uri alertSound = Uri.parse("android.resource://" + c.getPackageName() + "/" + R.raw.alert);

        AudioAttributes critAttrs = new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(Prefs.loud(c) ? AudioAttributes.USAGE_ALARM : AudioAttributes.USAGE_NOTIFICATION)
                .build();

        NotificationChannel crit = new NotificationChannel(
                CH_CRITICAL, c.getString(R.string.ch_crit_name), NotificationManager.IMPORTANCE_HIGH);
        crit.setDescription(c.getString(R.string.ch_crit_desc));
        crit.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        crit.setShowBadge(true);
        if (sound) {
            crit.setSound(alertSound, critAttrs);
            crit.enableVibration(true);
            crit.setVibrationPattern(new long[]{0, 450, 180, 450, 180, 650});
        } else {
            crit.setSound(null, null);
            crit.enableVibration(false);
        }
        nm.createNotificationChannel(crit);

        NotificationChannel gen = new NotificationChannel(
                CH_GENERAL, c.getString(R.string.ch_gen_name), NotificationManager.IMPORTANCE_HIGH);
        gen.setDescription(c.getString(R.string.ch_gen_desc));
        gen.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        gen.setShowBadge(true);
        if (sound) {
            gen.setSound(alertSound, critAttrs);
            gen.enableVibration(true);
            gen.setVibrationPattern(new long[]{0, 250, 150, 250});
        } else {
            gen.setSound(null, null);
            gen.enableVibration(false);
        }
        nm.createNotificationChannel(gen);

        NotificationChannel svc = new NotificationChannel(
                CH_SERVICE, c.getString(R.string.ch_svc_name), NotificationManager.IMPORTANCE_LOW);
        svc.setDescription(c.getString(R.string.ch_svc_desc));
        svc.setShowBadge(false);
        svc.setSound(null, null);
        nm.createNotificationChannel(svc);
    }

    // ── helpers ───────────────────────────────────────────────────────
    private static PendingIntent openApp(Context c, long msgId) {
        Intent i = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("msg_id", msgId);
        int req = (int) (msgId % 100000) + 10;
        return PendingIntent.getActivity(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent alertScreen(Context c, AlertItem item) {
        Intent i = new Intent(c, AlertActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("item", item.toJson());
        int req = (int) (item.id % 100000) + 100000;
        return PendingIntent.getActivity(c, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent dismissAction(Context c, AlertItem item) {
        Intent i = new Intent(c, AlertService.class)
                .setAction(AlertService.ACTION_DISMISS)
                .putExtra("notif_id", (int) (item.id % 100000));
        return PendingIntent.getService(c, (int) (item.id % 100000) + 200000, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Notification.Builder base(Context c, AlertItem it, String channel) {
        String body = it.body();
        if (TextUtils.isEmpty(body)) body = c.getString(R.string.no_text);
        Notification.Builder b = new Notification.Builder(c, channel)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(it.title())
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(
                        it.critical && !TextUtils.isEmpty(it.location)
                                ? body + "\n📍 " + it.location
                                : body))
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setAutoCancel(true)
                .setSubText(c.getString(R.string.app_name))
                .setColor(it.critical ? 0xFFE53935 : 0xFF0097A7)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(it.critical ? Notification.CATEGORY_ALARM : Notification.CATEGORY_MESSAGE)
                .setContentIntent(openApp(c, it.id))
                .addAction(new Notification.Action.Builder(
                        null, c.getString(R.string.action_hide), dismissAction(c, it)).build());

        if (!TextUtils.isEmpty(it.link)) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(it.link));
            PendingIntent vp = PendingIntent.getActivity(c, (int) (it.id % 100000) + 300000, view,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null, c.getString(R.string.action_telegram), vp).build());
        }
        return b;
    }

    /** Normal alert (heads-up / lock screen). */
    public static int post(Context c, AlertItem it, boolean fullScreenIntent) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return -1;
        String ch = it.critical ? CH_CRITICAL : CH_GENERAL;
        Notification.Builder b = base(c, it, ch);
        if (fullScreenIntent) {
            b.setFullScreenIntent(alertScreen(c, it), true);
            b.setCategory(Notification.CATEGORY_ALARM);
        }
        int id = notifId(it);
        try {
            nm.notify(id, b.build());
        } catch (SecurityException e) {
            // notifications not granted (Android 13+)
        }
        Prefs.setLastAlert(c, System.currentTimeMillis());
        return id;
    }

    /** One line instead of twenty when a burst of news arrives at once. */
    public static void postSummary(Context c, int count, String sample, boolean critical) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        String title = c.getString(R.string.burst_title, count);
        AlertItem it = new AlertItem();
        it.id = 0;
        it.display = c.getString(R.string.app_name);
        it.text = sample;
        it.critical = critical;
        Notification.Builder b = base(c, it, critical ? CH_CRITICAL : CH_GENERAL)
                .setContentTitle(title)
                .setStyle(new Notification.BigTextStyle()
                        .bigText(c.getString(R.string.burst_body, count) + "\n\n" + sample))
                .setAutoCancel(true);
        try {
            nm.notify(4321, b.build());
        } catch (SecurityException ignored) {}
    }

    /** Ongoing "the app is watching the news for you" notification. */
    public static Notification serviceNotification(Context c) {
        Intent open = new Intent(c, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_alert)
                .setContentTitle(c.getString(R.string.svc_title))
                .setContentText(c.getString(R.string.svc_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setColor(0xFF0097A7)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .build();
    }

    public static void cancel(Context c, int id) {
        if (id < 0) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(id);
    }
}
