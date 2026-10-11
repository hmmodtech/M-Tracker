package org.acf.mtracker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small SharedPreferences wrapper — the single source of truth for every
 * user setting. Read by the service, written by the settings screen.
 */
public final class Prefs {

    /** Default server. Can be changed any time from Settings. */
    public static final String DEFAULT_URL = "https://m-tracker-1.onrender.com/";

    private static final String FILE = "mtracker_prefs";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else e.putString(k, String.valueOf(v));
        e.apply();
    }

    // ── server ────────────────────────────────────────────────────────
    public static String url(Context c) {
        String u = sp(c).getString("url", DEFAULT_URL);
        if (u == null || u.trim().isEmpty()) u = DEFAULT_URL;
        u = u.trim();
        if (!u.startsWith("http")) u = "https://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }

    public static void setUrl(Context c, String v) { put(sp(c).edit(), "url", v == null ? "" : v.trim()); }

    /** Optional shared secret; must match STREAM_TOKEN on the server. */
    public static String token(Context c) { return sp(c).getString("token", ""); }
    public static void setToken(Context c, String v) { put(sp(c).edit(), "token", v == null ? "" : v.trim()); }

    /** Session cookie captured from the WebView login (forwarded to /api/stream). */
    public static String cookie(Context c) { return sp(c).getString("cookie", ""); }
    public static void setCookie(Context c, String v) { put(sp(c).edit(), "cookie", v == null ? "" : v); }

    // ── notifications ─────────────────────────────────────────────────
    /** "critical" (important news only) or "all" (every new message). */
    public static String scope(Context c) { return sp(c).getString("scope", "critical"); }
    public static void setScope(Context c, String v) { put(sp(c).edit(), "scope", v); }

    /** Wake the screen + show the news full screen when it arrives. */
    public static boolean takeover(Context c) { return sp(c).getBoolean("takeover", true); }
    public static void setTakeover(Context c, boolean v) { put(sp(c).edit(), "takeover", v); }

    /** Takeover for non-critical news too (off by default = calmer). */
    public static boolean takeoverAll(Context c) { return sp(c).getBoolean("takeover_all", false); }
    public static void setTakeoverAll(Context c, boolean v) { put(sp(c).edit(), "takeover_all", v); }

    /** Play the alert sound / vibrate. */
    public static boolean sound(Context c) { return sp(c).getBoolean("sound", true); }
    public static void setSound(Context c, boolean v) { put(sp(c).edit(), "sound", v); }

    /** Play the alert loudly even in silent / vibrate mode. */
    public static boolean loud(Context c) { return sp(c).getBoolean("loud", true); }
    public static void setLoud(Context c, boolean v) { put(sp(c).edit(), "loud", v); }

    // ── service ───────────────────────────────────────────────────────
    public static boolean serviceEnabled(Context c) { return sp(c).getBoolean("service", true); }
    public static void setServiceEnabled(Context c, boolean v) { put(sp(c).edit(), "service", v); }

    /** true = don't hold the CPU awake when the screen is off (slower alerts). */
    public static boolean batterySaver(Context c) { return sp(c).getBoolean("batt_saver", false); }
    public static void setBatterySaver(Context c, boolean v) { put(sp(c).edit(), "batt_saver", v); }

    /** Position in the message stream (last id the phone has already seen). */
    public static long cursor(Context c) { return sp(c).getLong("cursor", -1L); }
    public static void setCursor(Context c, long v) { put(sp(c).edit(), "cursor", v); }

    public static boolean setupSeen(Context c) { return sp(c).getBoolean("setup_seen", false); }
    public static void setSetupSeen(Context c, boolean v) { put(sp(c).edit(), "setup_seen", v); }

    /** Last state reported by the service: connected / offline / stopped ... */
    public static String status(Context c) { return sp(c).getString("status", "stopped"); }
    public static void setStatus(Context c, String v) { put(sp(c).edit(), "status", v); }

    public static long lastAlert(Context c) { return sp(c).getLong("last_alert", 0L); }
    public static void setLastAlert(Context c, long v) { put(sp(c).edit(), "last_alert", v); }
}
