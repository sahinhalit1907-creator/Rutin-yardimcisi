package com.gorev.asistani;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_FILE = 1;
    private static final int REQ_PERM = 2;
    private static final String RESUME_JS = "window.__nativeResume && window.__nativeResume()";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage: görevler telefonda burada saklanır
        s.setAllowFileAccess(true);

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient() {
            // confirm()/alert() pencereleri (silme, sıfırlama onayları)
            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("Tamam", (d, w) -> result.confirm())
                        .setNegativeButton("İptal", (d, w) -> result.cancel())
                        .show();
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setCancelable(false)
                        .setPositiveButton("Tamam", (d, w) -> result.confirm())
                        .show();
                return true;
            }

            // Yedekten geri yükle: dosya seçici
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        web.loadUrl("file:///android_asset/index.html");

        askPermissions(false);
        Scheduler.scheduleAll(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript(RESUME_JS, null);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        web.evaluateJavascript("(function(){return window.__nativeBack ? window.__nativeBack() : false;})()", v -> {
            if (!"true".equals(v)) finish();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            ensureExactAlarm(false);
            web.evaluateJavascript(RESUME_JS, null);
        }
    }

    /** Bildirim ve (gerekirse) tam zamanında alarm izinlerini ister. */
    private void askPermissions(boolean force) {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
        } else {
            ensureExactAlarm(force);
        }
    }

    private void ensureExactAlarm(boolean force) {
        if (Build.VERSION.SDK_INT < 31) return;
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (am.canScheduleExactAlarms()) return;
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (!force && sp.getBoolean("askedExact", false)) return;
        sp.edit().putBoolean("askedExact", true).apply();
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception ignored) {}
    }

    /** Web arayüzünün çağırdığı yerel fonksiyonlar (window.AndroidBridge). */
    private class Bridge {
        @JavascriptInterface
        public void syncReminders(String json) {
            Scheduler.sync(getApplicationContext(), json);
        }

        @JavascriptInterface
        public void requestPermissions() {
            runOnUiThread(() -> askPermissions(true));
        }

        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
                try { startActivity(i); } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String permissionStatus() {
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            boolean notif = nm.areNotificationsEnabled();
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            boolean exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            return "{\"notif\":" + notif + ",\"exact\":" + exact + "}";
        }

        @JavascriptInterface
        public void scheduleTest(int seconds) {
            Scheduler.scheduleTest(getApplicationContext(), seconds);
        }

        @JavascriptInterface
        public String scheduledInfo() {
            return Scheduler.info(getApplicationContext());
        }

        @JavascriptInterface
        public boolean batteryUnrestricted() {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        @JavascriptInterface
        public void openBatterySettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
                }
            });
        }

        @JavascriptInterface
        public void testNotification() {
            Scheduler.notifyNow(getApplicationContext(), 1, "Test bildirimi", "Bildirimler çalışıyor ✅");
        }

        /** Yedek dosyasını İndirilenler klasörüne yazar. */
        @JavascriptInterface
        public String saveBackup(String name, String data) {
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Downloads.DISPLAY_NAME, name);
                v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return "err";
                try (OutputStream o = getContentResolver().openOutputStream(u)) {
                    o.write(data.getBytes("UTF-8"));
                }
                return "ok";
            } catch (Exception e) {
                return "err:" + e.getMessage();
            }
        }
    }
}
