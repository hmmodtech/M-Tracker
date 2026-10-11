package org.acf.mtracker;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Last-resort crash recorder.
 *
 * The app must never die silently: whatever goes wrong is written to
 * files/crash.log (inside the app's private storage) and shown on the next
 * launch so it can be sent to whoever maintains the app.
 */
public final class CrashLog {

    private static final String FILE = "crash.log";
    private static final int MAX_BYTES = 24000;

    private CrashLog() {}

    public static synchronized void record(Context c, String where, Throwable t) {
        if (c == null || t == null) return;
        try {
            Context app = c.getApplicationContext();
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.println("==== " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
            pw.println("where : " + where);
            pw.println("app   : " + version(app));
            pw.println("device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE
                    + " (API " + Build.VERSION.SDK_INT + ")");
            pw.println("server: " + Prefs.url(app));
            t.printStackTrace(pw);
            pw.println();
            pw.flush();
            String entry = sw.toString();

            File f = new File(app.getFilesDir(), FILE);
            String old = readRaw(f);
            String all = entry + old;
            if (all.length() > MAX_BYTES) all = all.substring(0, MAX_BYTES);

            FileOutputStream os = new FileOutputStream(f, false);
            os.write(all.getBytes("UTF-8"));
            os.close();
        } catch (Throwable ignored) {
            // nothing left to do
        }
    }

    public static boolean has(Context c) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), FILE);
            return f.exists() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String read(Context c) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), FILE);
            return readRaw(f);
        } catch (Throwable t) {
            return "";
        }
    }

    public static void clear(Context c) {
        try {
            File f = new File(c.getApplicationContext().getFilesDir(), FILE);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {}
    }

    /** One-line summary for the settings screen. */
    public static String summary(Context c) {
        String all = read(c);
        if (all.isEmpty()) return null;
        String[] lines = all.split("\n");
        String when = lines.length > 0 ? lines[0].replace("=", "").trim() : "";
        String where = "";
        for (String l : lines) {
            if (l.startsWith("where :")) { where = l.substring(7).trim(); break; }
        }
        return when + (where.isEmpty() ? "" : "  ·  " + where);
    }

    private static String version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String readRaw(File f) {
        try {
            if (!f.exists()) return "";
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), MAX_BYTES)];
            int n = in.read(buf);
            in.close();
            return n > 0 ? new String(buf, 0, n, "UTF-8") : "";
        } catch (Throwable t) {
            return "";
        }
    }
}
