package org.acf.mtracker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** Tiny HTTP GET helper (no third-party libs — keeps the APK small). */
public final class Http {

    public static class Res {
        public int code;
        public String body = "";
    }

    private Http() {}

    public static Res get(String urlStr, String cookie, String token, int timeoutMs) throws IOException {
        HttpURLConnection cn = null;
        try {
            URL url = new URL(urlStr);
            cn = (HttpURLConnection) url.openConnection();
            cn.setRequestMethod("GET");
            cn.setConnectTimeout(Math.min(timeoutMs, 20000));
            cn.setReadTimeout(timeoutMs);
            cn.setInstanceFollowRedirects(true);
            cn.setRequestProperty("User-Agent", "M-Tracker-Android/1.0");
            cn.setRequestProperty("Accept", "application/json");
            cn.setRequestProperty("Accept-Encoding", "gzip, identity");
            cn.setRequestProperty("Connection", "close");
            if (cookie != null && !cookie.isEmpty()) cn.setRequestProperty("Cookie", cookie);
            if (token != null && !token.isEmpty()) cn.setRequestProperty("X-Stream-Token", token);

            Res r = new Res();
            r.code = cn.getResponseCode();
            InputStream in = (r.code >= 400) ? cn.getErrorStream() : cn.getInputStream();
            if (in == null) return r;
            String enc = cn.getContentEncoding();
            if (enc != null && enc.toLowerCase().contains("gzip")) in = new GZIPInputStream(in);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            br.close();
            r.body = sb.toString();
            return r;
        } finally {
            if (cn != null) cn.disconnect();
        }
    }
}
