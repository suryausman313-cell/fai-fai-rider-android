package com.faifai.rider;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 55;
    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable syncRider = new Runnable() {
        @Override public void run() {
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
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 8, 23));
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(settings.getUserAgentString() + " FaiFaiRider/2.0");
        webView.addJavascriptInterface(new RiderBridge(), "FaiFaiRider");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(
                    "document.documentElement.classList.add('fai-fai-rider-native');" +
                    "document.documentElement.dataset.faiFaiRiderNative='true';",
                    null
                );
                handler.removeCallbacks(syncRider);
                handler.post(syncRider);
            }
        });
        setContentView(webView);
        webView.loadUrl("https://fai-fai-juice.pages.dev/rider");

        requestNativeNotificationPermission();
    }

    private void requestNativeNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST &&
            (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            showNotificationSettingsDialog();
        }
    }

    private void showNotificationSettingsDialog() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
            .setTitle("Allow Rider notifications")
            .setMessage("New delivery alert aur Admin-selected ring ke liye Notifications ON karein.")
            .setNegativeButton("Later", null)
            .setPositiveButton("Open Settings", (dialog, which) -> openNotificationSettings())
            .show();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:" + getPackageName())));
        }
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(syncRider);
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    public final class RiderBridge {
        @JavascriptInterface public boolean isNativeApp() {
            return true;
        }

        @JavascriptInterface public void openNotificationSettings() {
            runOnUiThread(MainActivity.this::openNotificationSettings);
        }

        @JavascriptInterface public void configureRider(String raw) {
            String json = raw == null ? "" : raw;
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            }
            try {
                org.json.JSONObject rider = new org.json.JSONObject(json);
                int riderId = rider.optInt("id", 0);
                if (riderId > 0) {
                    getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                        .edit()
                        .putInt("rider_id", riderId)
                        .putString("name", rider.optString("name", "Rider"))
                        .apply();
                    startForegroundService(new Intent(MainActivity.this, RiderOrderService.class)
                        .setAction(RiderOrderService.ACTION_START));
                }
            } catch (Exception ignored) { }
        }
    }
}
