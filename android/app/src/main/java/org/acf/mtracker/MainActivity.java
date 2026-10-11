package org.acf.mtracker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * The web dashboard inside an Android shell + the glue that asks for the
 * permissions the alert system needs.
 *
 * Every setup step is wrapped: if one part fails the app still opens and
 * says what happened instead of closing itself.
 */
public class MainActivity extends Activity {

    private WebView web;
    private ProgressBar progress;
    private View offline;
    private TextView statusChip;
    private View statusDot;
    private View root;

    private long pendingMsgId = 0;

    private BroadcastReceiver statusReceiver;

    private static final int REQ_NOTIF_FIRST = 55;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            root = findViewById(android.R.id.content);
        } catch (Throwable t) {
            // even the layout failed: show something instead of a black screen
            CrashLog.record(this, "setContentView", t);
            setContentView(emergencyView(t));
            return;
        }

        try {
            Notifier.ensureChannels(this);
        } catch (Throwable t) {
            CrashLog.record(this, "ensureChannels", t);
        }

        try {
            web         = findViewById(R.id.web);
            progress    = findViewById(R.id.progress);
            offline     = findViewById(R.id.offline);
            statusChip  = findViewById(R.id.status_text);
            statusDot   = findViewById(R.id.status_dot);

            pendingMsgId = getIntent().getLongExtra("msg_id", 0);

            findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { loadFeed(true); }
            });
            findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openSettings(); }
            });
            findViewById(R.id.btn_settings2).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openSettings(); }
            });
            findViewById(R.id.btn_retry).setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    offline.setVisibility(View.GONE);
                    loadFeed(true);
                }
            });

            setupWebView();
            loadFeed(false);
        } catch (Throwable t) {
            CrashLog.record(this, "ui-setup", t);
            showFatal(t);
        }

        // start watching for news (never let this break the UI)
        try {
            if (Prefs.serviceEnabled(this)) AlertService.start(this);
            else AlertService.scheduleWatchdog(this);
        } catch (Throwable t) {
            CrashLog.record(this, "service-start", t);
            if (statusChip != null) statusChip.setText(R.string.status_stopped);
        }

        // ask for the notification permission early - without it nothing shows
        askNotificationPermissionIfNeeded();

        if (!Prefs.setupSeen(this)) {
            Prefs.setSetupSeen(this, true);
            showSetupDialog();
        }

        showCrashReportIfAny();
    }

    private View emergencyView(Throwable t) {
        TextView tv = new TextView(this);
        tv.setText("M-TRACKER\n\n" + getString(R.string.fatal_body) + "\n\n" + t);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(13f);
        tv.setPadding(40, 120, 40, 40);
        return tv;
    }

    private void showFatal(Throwable t) {
        try {
            if (offline != null) offline.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.crash_title)
                    .setMessage(String.valueOf(t))
                    .setPositiveButton(R.string.later, null)
                    .show();
        } catch (Throwable ignored) {}
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Throwable t) {
            CrashLog.record(this, "openSettings", t);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        long id = intent.getLongExtra("msg_id", 0);
        if (id > 0) { pendingMsgId = id; scrollToMessage(id); }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerStatusReceiver();
        try {
            setStatusIcon(Prefs.status(this));
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onPause() {
        unregisterStatusReceiver();
        super.onPause();
    }

    /**
     * IMPORTANT (Android 14 crash fix):
     * Since Android 14 a receiver registered for app-defined broadcasts MUST
     * pass RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED, otherwise
     * registerReceiver() throws SecurityException and the app dies the moment
     * it is opened. Our two actions are private to this app, so
     * RECEIVER_NOT_EXPORTED is the correct flag.
     */
    private void registerStatusReceiver() {
        if (statusReceiver != null) return;
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i == null) return;
                if (AlertService.ACTION_NEWS.equals(i.getAction())) {
                    runJs("if(typeof fetchMsgs==='function')fetchMsgs();");
                } else {
                    try { setStatusIcon(Prefs.status(MainActivity.this)); } catch (Throwable ignored) {}
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(AlertService.ACTION_STATUS);
        f.addAction(AlertService.ACTION_NEWS);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(r, f);
            }
            statusReceiver = r;
        } catch (Throwable t) {
            // never crash because of this - we just lose the live refresh
            CrashLog.record(this, "registerReceiver", t);
            statusReceiver = null;
        }
    }

    private void unregisterStatusReceiver() {
        if (statusReceiver == null) return;
        try {
            unregisterReceiver(statusReceiver);
        } catch (Throwable ignored) {}
        statusReceiver = null;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ── webview ───────────────────────────────────────────────────────
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setUserAgentString(s.getUserAgentString() + " M-TrackerApp/1.0");
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.WHITE);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                offline.setVisibility(View.GONE);
                rememberCookies();
                if (pendingMsgId > 0) {
                    final long id = pendingMsgId;
                    pendingMsgId = 0;
                    view.postDelayed(new Runnable() {
                        @Override public void run() { scrollToMessage(id); }
                    }, 1500);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req != null && req.isForMainFrame()) showOffline();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                if (failingUrl != null && failingUrl.equals(view.getUrl())) showOffline();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
                progress.setProgress(newProgress);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                          android.os.Message resultMsg) {
                // target="_blank" links (Telegram, maps...) -> open in their own app
                WebView.HitTestResult res = view.getHitTestResult();
                String url = res != null ? res.getExtra() : null;
                if (url != null && url.startsWith("http")) {
                    openExternally(url);
                    return true;
                }
                return false;
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                openExternally(url);
            }
        });
    }

    /** true = handled here (do not load inside the app) */
    private boolean handleUrl(String url) {
        if (url == null) return false;
        try {
            String base = Prefs.url(this);
            String host = Uri.parse(base).getHost();
            String targetHost = Uri.parse(url).getHost();
            if (targetHost != null && host != null && targetHost.equalsIgnoreCase(host)) {
                return false;   // our own dashboard: keep it inside the app
            }
            if (url.startsWith("http")) {
                openExternally(url);
                return true;
            }
        } catch (Throwable t) {
            CrashLog.record(this, "handleUrl", t);
        }
        return false;
    }

    private void openExternally(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Throwable e) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadFeed(boolean reload) {
        try {
            web.loadUrl(Prefs.url(this));
        } catch (Throwable t) {
            CrashLog.record(this, "loadUrl", t);
            showOffline();
        }
    }

    private void rememberCookies() {
        try {
            String c = CookieManager.getInstance().getCookie(Prefs.url(this));
            if (c != null && !c.isEmpty()) Prefs.setCookie(this, c);
        } catch (Throwable ignored) {}
    }

    private void runJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { web.evaluateJavascript(js, null); } catch (Throwable ignored) {}
            }
        });
    }

    /** New message tapped: scroll the dashboard to that card. */
    private void scrollToMessage(final long id) {
        final String js = "(function(){var n=0;var t=setInterval(function(){"
                + "var e=document.getElementById('mc-" + id + "');"
                + "if(e){clearInterval(t);e.scrollIntoView({behavior:'smooth',block:'center'});"
                + "e.style.transition='box-shadow .3s';e.style.boxShadow='0 0 0 3px #0097A7';"
                + "setTimeout(function(){e.style.boxShadow='';},4000);}"
                + "if(++n>40)clearInterval(t);},500);})();";
        runJs(js);
    }

    private void showOffline() {
        try {
            progress.setVisibility(View.GONE);
            offline.setVisibility(View.VISIBLE);
        } catch (Throwable ignored) {}
    }

    private void setStatusIcon(String status) {
        int color;
        int textId;
        if ("connected".equals(status))       { color = 0xFF22C55E; textId = R.string.status_connected; }
        else if ("connecting".equals(status)) { color = 0xFFF59E0B; textId = R.string.status_connecting; }
        else if ("token".equals(status))      { color = 0xFFE53935; textId = R.string.status_token; }
        else if ("server_old".equals(status)) { color = 0xFFE53935; textId = R.string.status_server_old; }
        else if ("blocked".equals(status))    { color = 0xFFE53935; textId = R.string.status_blocked; }
        else if ("stopped".equals(status))    { color = 0xFF9CA3AF; textId = R.string.status_stopped; }
        else                                  { color = 0xFFE53935; textId = R.string.status_offline; }
        if (statusDot != null) statusDot.setBackgroundColor(color);
        if (statusChip != null) statusChip.setText(textId);
    }

    // ── permissions / dialogs ─────────────────────────────────────────
    private void askNotificationPermissionIfNeeded() {
        try {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                       != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF_FIRST);
            }
        } catch (Throwable t) {
            CrashLog.record(this, "askNotifPermission", t);
        }
    }

    private void showSetupDialog() {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.setup_title)
                    .setMessage(R.string.setup_body)
                    .setPositiveButton(R.string.setup_open_settings, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { openSettings(); }
                    })
                    .setNegativeButton(R.string.later, null)
                    .show();
        } catch (Throwable ignored) {}
    }

    /** If the app died last time, say why and offer to send the details. */
    private void showCrashReportIfAny() {
        if (!CrashLog.has(this)) return;
        try {
            final String report = CrashLog.read(this);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.crash_title)
                    .setMessage(getString(R.string.crash_intro) + "\n\n" + report)
                    .setPositiveButton(R.string.crash_send, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { shareText(report); }
                    })
                    .setNeutralButton(R.string.crash_clear, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) { CrashLog.clear(MainActivity.this); }
                    })
                    .setNegativeButton(R.string.later, null)
                    .show();
        } catch (Throwable ignored) {}
    }

    static void shareText(Activity a, String text) {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "M-TRACKER crash report");
            i.putExtra(Intent.EXTRA_TEXT, text);
            a.startActivity(Intent.createChooser(i, a.getString(R.string.crash_send)));
        } catch (Throwable ignored) {}
    }

    private void shareText(String text) { shareText(this, text); }

    /** Called from the settings screen "test alert" button. */
    static AlertItem sample() {
        AlertItem it = new AlertItem();
        it.id = System.currentTimeMillis() / 1000;
        it.channel = "test";
        it.display = "M-TRACKER";
        it.text = "اختبار: هيك رح يطلعلك الخبر لما تكون الشاشة مقفلة";
        it.critical = true;
        it.date = "";
        return it;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        try { Notifier.ensureChannels(this); } catch (Throwable ignored) {}
    }
}
