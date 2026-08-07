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
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_FOREGROUND_PERMISSIONS = 55;
    private static final int REQ_BACKGROUND_LOCATION = 56;

    private WebView webView;
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
        settings.setUserAgentString(settings.getUserAgentString() + " FaiFaiRider/1.3");
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
