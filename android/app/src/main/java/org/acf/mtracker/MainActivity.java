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
 */
public class MainActivity extends Activity {

    private WebView web;
    private ProgressBar progress;
    private View offline;
    private TextView statusChip;
    private View statusDot;

    private long pendingMsgId = 0;
    private boolean lastLoadFailed = false;

    private BroadcastReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Notifier.ensureChannels(this);
        setContentView(R.layout.activity_main);

        web      = findViewById(R.id.web);
        progress = findViewById(R.id.progress);
        offline  = findViewById(R.id.offline);
        statusChip = findViewById(R.id.status_text);
        statusDot  = findViewById(R.id.status_dot);

        pendingMsgId = getIntent().getLongExtra("msg_id", 0);

        findViewById(R.id.btn_refresh).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { loadFeed(true); }
        });
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });
        findViewById(R.id.btn_retry).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { offline.setVisibility(View.GONE); loadFeed(true); }
        });
        findViewById(R.id.btn_settings2).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        setupWebView();
        loadFeed(false);

        if (Prefs.serviceEnabled(this)) AlertService.start(this);
        else AlertService.scheduleWatchdog(this);

        if (!Prefs.setupSeen(this)) {
            Prefs.setSetupSeen(this, true);
            showSetupDialog();
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
        statusReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (AlertService.ACTION_NEWS.equals(i.getAction())) {
                    // keep the open dashboard in sync with the live feed
                    runJs("if(typeof fetchMsgs==='function')fetchMsgs();");
                } else {
                    setStatusIcon(Prefs.status(MainActivity.this));
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(AlertService.ACTION_STATUS);
        f.addAction(AlertService.ACTION_NEWS);
        registerReceiver(statusReceiver, f);
        setStatusIcon(Prefs.status(this));
    }

    @Override
    protected void onPause() {
        if (statusReceiver != null) {
            try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
            statusReceiver = null;
        }
        super.onPause();
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
                lastLoadFailed = false;
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
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
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
        return false;
    }

    private void openExternally(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private void loadFeed(boolean reload) {
        web.loadUrl(Prefs.url(this));
    }

    private void rememberCookies() {
        try {
            String c = CookieManager.getInstance().getCookie(Prefs.url(this));
            if (c != null && !c.isEmpty()) Prefs.setCookie(this, c);
        } catch (Exception ignored) {}
    }

    private void runJs(final String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { web.evaluateJavascript(js, null); } catch (Exception ignored) {}
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
        lastLoadFailed = true;
        progress.setVisibility(View.GONE);
        offline.setVisibility(View.VISIBLE);
    }

    private void setStatusIcon(String status) {
        int color;
        int textId;
        if ("connected".equals(status))      { color = 0xFF22C55E; textId = R.string.status_connected; }
        else if ("connecting".equals(status)){ color = 0xFFF59E0B; textId = R.string.status_connecting; }
        else if ("token".equals(status))     { color = 0xFFE53935; textId = R.string.status_token; }
        else if ("stopped".equals(status))   { color = 0xFF9CA3AF; textId = R.string.status_stopped; }
        else                                 { color = 0xFFE53935; textId = R.string.status_offline; }
        if (statusDot != null) statusDot.setBackgroundColor(color);
        if (statusChip != null) statusChip.setText(textId);
    }

    // ── permissions ───────────────────────────────────────────────────
    private void showSetupDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_title)
                .setMessage(R.string.setup_body)
                .setPositiveButton(R.string.setup_open_settings, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        startActivity(new Intent(MainActivity.this, SettingsActivity.class));
                    }
                })
                .setNegativeButton(R.string.later, null)
                .show();
    }

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
}
