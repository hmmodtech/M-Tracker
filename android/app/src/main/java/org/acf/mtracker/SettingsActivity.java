package org.acf.mtracker;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import android.content.pm.PackageInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Server URL, notification behaviour, the monitoring switch and a shortcut
 *  for every permission the locked-screen alert needs. */
public class SettingsActivity extends Activity {

    private EditText etUrl, etToken;
    private RadioButton rbCritical, rbAll;
    private Switch swTakeover, swTakeoverAll, swSound, swLoud, swService, swBattery;
    private Button btnNotif, btnOverlay, btnBattery, btnFsi, btnCrash, btnRestart;
    private TextView txtState, txtAbout;

    private static final int REQ_NOTIF = 101;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_settings);
        } catch (Throwable t) {
            CrashLog.record(this, "settings.setContentView", t);
            TextView tv = new TextView(this);
            tv.setText(String.valueOf(t));
            tv.setPadding(40, 120, 40, 40);
            setContentView(tv);
            return;
        }

        etUrl          = findViewById(R.id.et_url);
        etToken        = findViewById(R.id.et_token);
        rbCritical     = findViewById(R.id.rb_critical);
        rbAll          = findViewById(R.id.rb_all);
        swTakeover     = findViewById(R.id.sw_takeover);
        swTakeoverAll  = findViewById(R.id.sw_takeover_all);
        swSound        = findViewById(R.id.sw_sound);
        swLoud         = findViewById(R.id.sw_loud);
        swService      = findViewById(R.id.sw_service);
        swBattery      = findViewById(R.id.sw_battery);
        btnNotif       = findViewById(R.id.btn_notif);
        btnOverlay     = findViewById(R.id.btn_overlay);
        btnBattery     = findViewById(R.id.btn_battery);
        btnFsi         = findViewById(R.id.btn_fsi);
        txtState       = findViewById(R.id.txt_state);
        txtAbout       = findViewById(R.id.txt_about);

        btnCrash   = findViewById(R.id.btn_crash);
        btnRestart = findViewById(R.id.btn_restart);
        txtAbout.setText(getString(R.string.about, versionName()));
        load();

        // ── server ────────────────────────────────────────────────────
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setUrl(SettingsActivity.this, etUrl.getText().toString());
                Prefs.setToken(SettingsActivity.this, etToken.getText().toString());
                Prefs.setCursor(SettingsActivity.this, -1L);      // re-baseline on the new server
                etUrl.setText(Prefs.url(SettingsActivity.this));
                Toast.makeText(SettingsActivity.this, R.string.saved, Toast.LENGTH_SHORT).show();
                if (Prefs.serviceEnabled(SettingsActivity.this)) AlertService.start(SettingsActivity.this);
            }
        });

        // ── notification behaviour ────────────────────────────────────
        View.OnClickListener scope = new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setScope(SettingsActivity.this, rbAll.isChecked() ? "all" : "critical");
                Prefs.setCursor(SettingsActivity.this, -1L);      // scope changed -> re-baseline
                if (Prefs.serviceEnabled(SettingsActivity.this)) AlertService.start(SettingsActivity.this);
            }
        };
        rbCritical.setOnClickListener(scope);
        rbAll.setOnClickListener(scope);

        swTakeover.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Prefs.setTakeover(SettingsActivity.this, swTakeover.isChecked()); }
        });
        swTakeoverAll.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { Prefs.setTakeoverAll(SettingsActivity.this, swTakeoverAll.isChecked()); }
        });
        swSound.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setSound(SettingsActivity.this, swSound.isChecked());
                Notifier.ensureChannels(SettingsActivity.this);
            }
        });
        swLoud.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setLoud(SettingsActivity.this, swLoud.isChecked());
                Notifier.ensureChannels(SettingsActivity.this);
            }
        });

        // ── service ───────────────────────────────────────────────────
        swService.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean on = swService.isChecked();
                Prefs.setServiceEnabled(SettingsActivity.this, on);
                if (on) {
                    AlertService.start(SettingsActivity.this);
                } else {
                    AlertService.cancelWatchdog(SettingsActivity.this);
                    AlertService.stop(SettingsActivity.this);
                }
                refreshState();
            }
        });
        swBattery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setBatterySaver(SettingsActivity.this, swBattery.isChecked());
            }
        });

        findViewById(R.id.btn_test).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                testAlert();
            }
        });

        // ── permissions ───────────────────────────────────────────────
        btnNotif.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { askNotifications(); }
        });
        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    toast(getString(R.string.perm_overlay));
                }
            }
        });
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    } catch (Exception e2) {
                        toast(getString(R.string.perm_battery));
                    }
                }
            }
        });
        btnFsi.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (Build.VERSION.SDK_INT >= 34) {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        toast(getString(R.string.perm_fsi));
                    }
                } else {
                    toast(getString(R.string.perm_ok));
                }
            }
        });

        btnCrash.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String log = CrashLog.read(SettingsActivity.this);
                if (log == null || log.trim().isEmpty()) {
                    toast(getString(R.string.crash_none));
                    return;
                }
                MainActivity.shareText(SettingsActivity.this, log);
            }
        });

        btnRestart.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Prefs.setServiceEnabled(SettingsActivity.this, true);
                Prefs.setCursor(SettingsActivity.this, -1L);
                AlertService.start(SettingsActivity.this);
                toast(getString(R.string.status_connecting));
                refreshState();
            }
        });

        askNotificationsIfNeeded();
    }

    private String versionName() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    // ── helpers ───────────────────────────────────────────────────────
    private void load() {
        etUrl.setText(Prefs.url(this));
        etToken.setText(Prefs.token(this));
        boolean all = "all".equals(Prefs.scope(this));
        rbAll.setChecked(all);
        rbCritical.setChecked(!all);
        swTakeover.setChecked(Prefs.takeover(this));
        swTakeoverAll.setChecked(Prefs.takeoverAll(this));
        swSound.setChecked(Prefs.sound(this));
        swLoud.setChecked(Prefs.loud(this));
        swService.setChecked(Prefs.serviceEnabled(this));
        swBattery.setChecked(Prefs.batterySaver(this));
    }

    private void refreshState() {
        String status;
        String hint = null;
        switch (Prefs.status(this)) {
            case "connected":  status = getString(R.string.status_connected); break;
            case "connecting": status = getString(R.string.status_connecting); break;
            case "token":      status = getString(R.string.status_token); break;
            case "server_old": status = getString(R.string.status_server_old); hint = getString(R.string.status_server_old); break;
            case "blocked":    status = getString(R.string.status_blocked); hint = getString(R.string.blocked_body); break;
            case "stopped":    status = getString(R.string.status_stopped); break;
            default:           status = getString(R.string.status_offline);
        }
        long last = Prefs.lastAlert(this);
        String lastTxt = getString(R.string.never);
        if (last > 0) {
            lastTxt = new SimpleDateFormat("HH:mm  dd/MM", Locale.US).format(new Date(last));
        }
        txtState.setText(getString(R.string.state_line, status, lastTxt) + (hint == null ? "" : "\n\n" + hint));

        String crash = CrashLog.summary(this);
        btnCrash.setText(crash == null ? getString(R.string.crash_none) : getString(R.string.crash_view) + "\n" + crash);

        // permission rows
        boolean notif = hasNotifications();
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        boolean battery = isIgnoringBattery();
        boolean fsi = fullScreenGranted();

        btnNotif.setText(getString(R.string.perm_notif) + "   " + (notif ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));
        btnOverlay.setText(getString(R.string.perm_overlay) + "   " + (overlay ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));
        btnBattery.setText(getString(R.string.perm_battery) + "   " + (battery ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));
        btnFsi.setText(getString(R.string.perm_fsi) + "   " + (fsi ? getString(R.string.perm_ok) : getString(R.string.perm_missing)));

        swService.setChecked(Prefs.serviceEnabled(this));
    }

    private boolean hasNotifications() {
        if (Build.VERSION.SDK_INT < 33) {
            return getSystemService(android.app.NotificationManager.class) != null;
        }
        return checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBattery() {
        try {
            android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean fullScreenGranted() {
        if (Build.VERSION.SDK_INT < 34) return true;
        try {
            return getSystemService(android.app.NotificationManager.class).canUseFullScreenIntent();
        } catch (Throwable t) {
            return true;
        }
    }

    private void askNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) askNotifications();
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
        } else {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (Exception e) {
                toast(getString(R.string.perm_notif));
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshState();
    }

    /** Fire a real alert through the exact same path a live one takes. */
    private void testAlert() {
        AlertItem it = MainActivity.sample();
        boolean locked = !isInteractive() || isKeyguardLocked();
        Notifier.post(this, it, true);
        Alerts.push(it);
        try {
            startActivity(new Intent(this, AlertActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("item", it.toJson()));
        } catch (Exception e) {
            toast(getString(R.string.test_body));
        }
    }

    private boolean isInteractive() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    private boolean isKeyguardLocked() {
        android.app.KeyguardManager km = getSystemService(android.app.KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
