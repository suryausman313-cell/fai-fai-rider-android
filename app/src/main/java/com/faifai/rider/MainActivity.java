package com.faifai.rider;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int PICK_RINGTONE = 801;
    private static final int REQ_FOREGROUND_PERMISSIONS = 55;
    private static final int REQ_BACKGROUND_LOCATION = 56;

    private WebView webView;
    private android.media.Ringtone preview;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable sync = new Runnable() {
        @Override
        public void run() {
            if (webView != null) {
                webView.evaluateJavascript(
                        "(function(){try{return localStorage.getItem('rider_auth')||''}catch(e){return ''}})()",
                        value -> new RiderBridge().configureRider(value)
                );
            }
            handler.postDelayed(this, 5000);
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestNeededPermissions();

        // If the rider has already logged in before, make sure the native
        // foreground service is running again whenever the app is opened.
        int savedRiderId = getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                .getInt("rider_id", 0);
        if (savedRiderId > 0) {
            startRiderService();
        }

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 8, 23));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " FaiFaiRider/1.2");
        webView.addJavascriptInterface(new RiderBridge(), "FaiFaiRider");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                handler.removeCallbacks(sync);
                handler.post(sync);
            }
        });

        FrameLayout root = new FrameLayout(this);
        root.addView(webView, new FrameLayout.LayoutParams(-1, -1));

        // Keep the existing custom rider-ring control unchanged.
        Button bell = new Button(this);
        bell.setText("🔔");
        bell.setTextSize(21);
        bell.setContentDescription("Rider ringtone settings");
        bell.setOnClickListener(v -> showRingSettings());
        FrameLayout.LayoutParams bellParams = new FrameLayout.LayoutParams(
                dp(58), dp(58), android.view.Gravity.END | android.view.Gravity.BOTTOM
        );
        bellParams.setMargins(0, 0, dp(16), dp(22));
        root.addView(bell, bellParams);

        setContentView(root);
        webView.loadUrl("https://fai-fai-juice.pages.dev/rider");
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        if (!permissions.isEmpty()) {
            requestPermissions(
                    permissions.toArray(new String[0]),
                    REQ_FOREGROUND_PERMISSIONS
            );
        } else {
            requestBackgroundLocationIfNeeded();
        }
    }

    private void requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT < 29) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
            startRiderServiceIfLoggedIn();
            return;
        }

        if (Build.VERSION.SDK_INT == 29) {
            requestPermissions(
                    new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                    REQ_BACKGROUND_LOCATION
            );
            return;
        }

        // Android 11+ normally requires "Allow all the time" to be enabled
        // from the app's Location permission settings. Show this once only.
        SharedPreferences prefs = getSharedPreferences("fai_fai_rider", MODE_PRIVATE);
        if (prefs.getBoolean("background_location_prompted", false)) return;
        prefs.edit().putBoolean("background_location_prompted", true).apply();

        new AlertDialog.Builder(this)
                .setTitle("Keep Rider Location Live")
                .setMessage("For live rider tracking when the screen is off, open Permissions → Location and choose Allow all the time.")
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    Intent intent = new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())
                    );
                    startActivity(intent);
                })
                .setNegativeButton("Later", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_FOREGROUND_PERMISSIONS) {
            requestBackgroundLocationIfNeeded();
            startRiderServiceIfLoggedIn();
        } else if (requestCode == REQ_BACKGROUND_LOCATION) {
            startRiderServiceIfLoggedIn();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startRiderServiceIfLoggedIn();
    }

    private void startRiderServiceIfLoggedIn() {
        int riderId = getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                .getInt("rider_id", 0);
        if (riderId > 0) startRiderService();
    }

    private void startRiderService() {
        Intent intent = new Intent(this, RiderOrderService.class)
                .setAction(RiderOrderService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }

    private void showRingSettings() {
        SharedPreferences prefs = getSharedPreferences("fai_fai_rider", MODE_PRIVATE);
        boolean on = prefs.getBoolean("native_sound", true);
        String[] actions = {
                "Choose rider ringtone",
                "Test selected ringtone",
                on ? "Turn ring OFF" : "Turn ring ON",
                "Stop test"
        };
        new AlertDialog.Builder(this)
                .setTitle("Rider Ring Settings")
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) pickRing();
                    else if (which == 1) testRing();
                    else if (which == 2) {
                        prefs.edit().putBoolean("native_sound", !on).apply();
                        Toast.makeText(
                                this,
                                !on ? "Rider ring ON" : "Rider ring OFF",
                                Toast.LENGTH_SHORT
                        ).show();
                    } else {
                        stopPreview();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void pickRing() {
        SharedPreferences prefs = getSharedPreferences("fai_fai_rider", MODE_PRIVATE);
        String saved = prefs.getString("ringtone_uri", "");
        Intent intent = new Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_TYPE,
                android.media.RingtoneManager.TYPE_ALARM
                        | android.media.RingtoneManager.TYPE_RINGTONE
                        | android.media.RingtoneManager.TYPE_NOTIFICATION
        );
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose Rider Ring");
        intent.putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(
                android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                saved == null || saved.isEmpty()
                        ? android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM)
                        : Uri.parse(saved)
        );
        startActivityForResult(intent, PICK_RINGTONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_RINGTONE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getParcelableExtra(
                    android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI
            );
            if (uri != null) {
                getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                        .edit()
                        .putString("ringtone_uri", uri.toString())
                        .putBoolean("native_sound", true)
                        .apply();
                testRing();
            }
        }
    }

    private void testRing() {
        stopPreview();
        String saved = getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                .getString("ringtone_uri", "");
        Uri uri = saved == null || saved.isEmpty()
                ? android.media.RingtoneManager.getDefaultUri(
                android.media.RingtoneManager.TYPE_ALARM)
                : Uri.parse(saved);
        preview = android.media.RingtoneManager.getRingtone(this, uri);
        if (preview != null) preview.play();
    }

    private void stopPreview() {
        if (preview != null) {
            try {
                preview.stop();
            } catch (Exception ignored) {
            }
            preview = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(sync);
        stopPreview();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public final class RiderBridge {
        @JavascriptInterface
        public void configureRider(String raw) {
            String json = raw == null ? "" : raw;
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }

            if (json.isEmpty() || "null".equalsIgnoreCase(json)) return;

            try {
                org.json.JSONObject object = new org.json.JSONObject(json);
                int riderId = object.optInt("id", 0);
                if (riderId > 0) {
                    getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                            .edit()
                            .putInt("rider_id", riderId)
                            .putString("name", object.optString("name", "Rider"))
                            .apply();
                    startRiderService();
                }
            } catch (Exception ignored) {
            }
        }
    }
}
