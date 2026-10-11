package org.acf.mtracker;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The full-screen alert: lights the screen up, shows the news on top of the
 * lock screen, and lets the user jump straight into the app.
 */
public class AlertActivity extends Activity {

    private static final long AUTO_CLOSE_MS = 90 * 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable autoClose = new Runnable() {
        @Override public void run() { finish(); }
    };

    private TextView tvChannel, tvTime, tvBody, tvLoc, tvCritical;
    private Button btnOpen, btnNext, btnClose;
    private LinearLayout boxLoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showOverLockScreen();
        setContentView(R.layout.activity_alert);

        tvChannel  = findViewById(R.id.alert_channel);
        tvTime     = findViewById(R.id.alert_time);
        tvBody     = findViewById(R.id.alert_body);
        tvLoc      = findViewById(R.id.alert_location);
        tvCritical = findViewById(R.id.alert_critical);
        boxLoc     = findViewById(R.id.alert_loc_box);
        btnOpen    = findViewById(R.id.alert_open);
        btnNext    = findViewById(R.id.alert_next);
        btnClose   = findViewById(R.id.alert_close);

        AlertItem item = itemFromIntent(getIntent());
        if (item == null) item = Alerts.poll();
        if (item == null) { finish(); return; }
        bind(item);

        btnOpen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openApp(itemId); }
        });
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showNext(); }
        });
        if (boxLoc != null) {
            boxLoc.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { openMap(); }
            });
        }
        refreshNextButton();
        handler.postDelayed(autoClose, AUTO_CLOSE_MS);
    }

    private long itemId = 0;
    private String itemGmaps = null;

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AlertItem item = itemFromIntent(intent);
        if (item != null) {
            bind(item);
            refreshNextButton();
            handler.removeCallbacks(autoClose);
            handler.postDelayed(autoClose, AUTO_CLOSE_MS);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(autoClose);
        super.onDestroy();
    }

    // ── helpers ───────────────────────────────────────────────────────
    private void showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private AlertItem itemFromIntent(Intent intent) {
        if (intent == null) return null;
        String js = intent.getStringExtra("item");
        if (js == null) return null;
        try {
            return AlertItem.fromJson(new JSONObject(js));
        } catch (Exception e) {
            return null;
        }
    }

    private void bind(AlertItem item) {
        itemId = item.id;
        Alerts.removeId(item.id);
        itemGmaps = item.gmaps;
        tvChannel.setText(item.title());
        tvTime.setText(formatTime(item.date));
        tvBody.setText(item.body());
        tvCritical.setVisibility(item.critical ? View.VISIBLE : View.GONE);
        if (item.location != null && !item.location.trim().isEmpty()) {
            boxLoc.setVisibility(View.VISIBLE);
            tvLoc.setText("📍 " + item.location);
        } else {
            boxLoc.setVisibility(View.GONE);
        }
        // clear the matching notification from the shade — it is on screen now
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(Notifier.notifId(item));
    }

    private void showNext() {
        AlertItem next = Alerts.poll();
        if (next == null) { finish(); return; }
        bind(next);
        refreshNextButton();
    }

    private void refreshNextButton() {
        btnNext.setVisibility(Alerts.size() > 0 ? View.VISIBLE : View.GONE);
    }

    private void openApp(long msgId) {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("msg_id", msgId);
        startActivity(i);
        finish();
    }

    private void openMap() {
        try {
            String url = (itemGmaps != null && !itemGmaps.isEmpty())
                    ? itemGmaps
                    : "https://www.google.com/maps?q=" + Uri.encode(tvLoc.getText().toString());
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show();
        }
    }

    private String formatTime(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        }
        try {
            String s = raw.replace('T', ' ');
            if (s.length() > 19) s = s.substring(0, 19);
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Date d = in.parse(s);
            SimpleDateFormat out = new SimpleDateFormat("HH:mm", Locale.US);
            return d != null ? out.format(d) : raw;
        } catch (Exception e) {
            return raw;
        }
    }
}
