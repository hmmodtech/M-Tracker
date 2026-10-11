package org.acf.mtracker;

import org.json.JSONObject;

import java.io.Serializable;

/** One news item pushed by the server. */
public class AlertItem implements Serializable {

    public long   id;
    public String channel  = "";
    public String display  = "";
    public String text     = "";
    public String summary  = "";
    public boolean critical;
    public String link     = "";
    public String date     = "";
    public String location = "";
    public String coords   = "";
    public String gmaps    = "";

    public static AlertItem fromJson(JSONObject o) {
        AlertItem a = new AlertItem();
        a.id       = o.optLong("id");
        a.channel  = o.optString("channel", "");
        a.display  = o.optString("display", "M-TRACKER");
        a.text     = o.optString("text", "");
        a.summary  = o.optString("summary", "");
        a.critical = o.optInt("critical", 0) == 1;
        a.link     = o.optString("link", "");
        a.date     = o.optString("date", "");
        a.location = o.optString("location", "");
        a.coords   = o.optString("coords", "");
        a.gmaps    = o.optString("gmaps", "");
        return a;
    }

    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("channel", channel);
            o.put("display", display);
            o.put("text", text);
            o.put("summary", summary);
            o.put("critical", critical ? 1 : 0);
            o.put("link", link);
            o.put("date", date);
            o.put("location", location);
            o.put("coords", coords);
            o.put("gmaps", gmaps);
        } catch (Exception ignored) {}
        return o.toString();
    }

    /** Text that goes into the notification / full screen alert. */
    public String body() {
        String t = (summary != null && !summary.trim().isEmpty()) ? summary : text;
        if (t == null) t = "";
        t = t.replaceAll("https?://\\S+", "").replaceAll("\\s{2,}", " ").trim();
        return t;
    }

    public String title() {
        return (display == null || display.trim().isEmpty()) ? "M-TRACKER" : display;
    }
}
