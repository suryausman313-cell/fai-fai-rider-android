package com.faifai.rider;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.json.JSONTokener;

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
                        "(function(){try{return JSON.stringify({rider:localStorage.getItem('rider_auth')||'',token:localStorage.getItem('rider_access_token')||''})}catch(e){return ''}})()",
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

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(2, 8, 23));

        WebSettings settings = webView.getSettings();

        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setUserAgentString(
                settings.getUserAgentString() + " FaiFaiRider/1.3.0"
        );

        webView.addJavascriptInterface(
                new RiderBridge(),
                "FaiFaiRider"
        );

        webView.setWebChromeClient(new WebChromeClient());

        webView.setWebViewClient(new WebViewClient() {

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    WebResourceRequest request
            ) {
                if (request == null || request.getUrl() == null) {
                    return false;
                }

                return handleExternalUrl(
                        request.getUrl().toString()
                );
            }

            @Override
            public boolean shouldOverrideUrlLoading(
                    WebView view,
                    String url
            ) {
                return handleExternalUrl(url);
            }

            @Override
            public void onPageFinished(
                    WebView view,
                    String url
            ) {
                super.onPageFinished(view, url);

                handler.removeCallbacks(sync);
                handler.post(sync);
            }
        });

        FrameLayout root = new FrameLayout(this);

        root.addView(
                webView,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        );

        setContentView(root);

        webView.loadUrl(
                "https://fai-fai-juice.pages.dev/rider"
        );
    }


    /*
     * =========================================================
     * GOOGLE MAPS / NAVIGATION LINK FIX
     * =========================================================
     *
     * Fixes:
     * net::ERR_UNKNOWN_URL_SCHEME
     *
     * Handles:
     * intent://
     * google.navigation:
     * geo:
     * market:
     * tel:
     * whatsapp:
     *
     * Normal https/http links stay inside the Rider WebView.
     */
    private boolean handleExternalUrl(String url) {

        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        try {

            // Google Maps / Android intent links
            if (url.startsWith("intent://")) {

                try {
                    Intent intent = Intent.parseUri(
                            url,
                            Intent.URI_INTENT_SCHEME
                    );

                    intent.addCategory(
                            Intent.CATEGORY_BROWSABLE
                    );

                    intent.setComponent(null);
                    intent.setSelector(null);

                    try {
                        startActivity(intent);
                        return true;

                    } catch (ActivityNotFoundException e) {

                        String fallbackUrl =
                                intent.getStringExtra(
                                        "browser_fallback_url"
                                );

                        if (fallbackUrl != null
                                && !fallbackUrl.isEmpty()) {

                            openExternalHttps(fallbackUrl);
                            return true;
                        }

                        // Last fallback:
                        // Open Google Maps website.
                        openExternalHttps(
                                "https://www.google.com/maps"
                        );

                        return true;
                    }

                } catch (URISyntaxException e) {

                    openExternalHttps(
                            "https://www.google.com/maps"
                    );

                    return true;
                }
            }


            // Native Google Maps Navigation link
            if (url.startsWith("google.navigation:")) {

                Intent navigationIntent =
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url)
                        );

                navigationIntent.setPackage(
                        "com.google.android.apps.maps"
                );

                try {
                    startActivity(navigationIntent);

                } catch (ActivityNotFoundException e) {

                    Intent fallback =
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(url)
                            );

                    startActivity(fallback);
                }

                return true;
            }


            // Geo / Maps coordinates
            if (url.startsWith("geo:")) {

                Intent geoIntent =
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url)
                        );

                try {
                    geoIntent.setPackage(
                            "com.google.android.apps.maps"
                    );

                    startActivity(geoIntent);

                } catch (ActivityNotFoundException e) {

                    Intent fallback =
                            new Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse(url)
                            );

                    startActivity(fallback);
                }

                return true;
            }


            // Phone / WhatsApp / market / other app links
            if (url.startsWith("tel:")
                    || url.startsWith("market:")
                    || url.startsWith("whatsapp:")
                    || url.startsWith("mailto:")) {

                Intent externalIntent =
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(url)
                        );

                try {
                    startActivity(externalIntent);
                } catch (ActivityNotFoundException ignored) {
                }

                return true;
            }


            /*
             * Normal website links:
             * Keep them inside Rider WebView.
             */
            if (url.startsWith("http://")
                    || url.startsWith("https://")) {

                return false;
            }


            /*
             * Any other unknown scheme:
             * Try Android instead of showing
             * ERR_UNKNOWN_URL_SCHEME inside WebView.
             */
            Intent externalIntent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)
                    );

            try {
                startActivity(externalIntent);
                return true;

            } catch (ActivityNotFoundException ignored) {
                return false;
            }

        } catch (Exception e) {
            return false;
        }
    }


    private void openExternalHttps(String url) {

        try {
            Intent browserIntent =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(url)
                    );

            startActivity(browserIntent);

        } catch (Exception ignored) {
        }
    }


    private void requestNeededPermissions() {

        List<String> permissions =
                new ArrayList<>();

        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.ACCESS_FINE_LOCATION
            );
        }

        if (checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.ACCESS_COARSE_LOCATION
            );
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {

            permissions.add(
                    Manifest.permission.POST_NOTIFICATIONS
            );
        }

        if (!permissions.isEmpty()) {

            requestPermissions(
                    permissions.toArray(
                            new String[0]
                    ),
                    REQ_FOREGROUND_PERMISSIONS
            );

        } else {

            requestBackgroundLocationIfNeeded();
        }
    }


    private void requestBackgroundLocationIfNeeded() {

        if (Build.VERSION.SDK_INT < 29) {
            return;
        }

        if (checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
        ) != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        if (checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED) {

            startRiderServiceIfLoggedIn();
            return;
        }


        if (Build.VERSION.SDK_INT == 29) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    },
                    REQ_BACKGROUND_LOCATION
            );

            return;
        }


        // Android 11+
        SharedPreferences prefs =
                getSharedPreferences(
                        "fai_fai_rider",
                        MODE_PRIVATE
                );

        if (prefs.getBoolean(
                "background_location_prompted",
                false
        )) {
            return;
        }

        prefs.edit()
                .putBoolean(
                        "background_location_prompted",
                        true
                )
                .apply();


        new AlertDialog.Builder(this)
                .setTitle(
                        "Keep Rider Location Live"
                )
                .setMessage(
                        "For live rider tracking when the screen is off, open Permissions → Location and choose Allow all the time."
                )
                .setPositiveButton(
                        "Open Settings",
                        (dialog, which) -> {

                            Intent intent =
                                    new Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse(
                                                    "package:"
                                                            + getPackageName()
                                            )
                                    );

                            startActivity(intent);
                        }
                )
                .setNegativeButton(
                        "Later",
                        null
                )
                .show();
    }


    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {

        super.onRequestPermissionsResult(
                requestCode,
                permissions,
                grantResults
        );

        if (requestCode
                == REQ_FOREGROUND_PERMISSIONS) {

            requestBackgroundLocationIfNeeded();
            startRiderServiceIfLoggedIn();

        } else if (
                requestCode
                        == REQ_BACKGROUND_LOCATION
        ) {

            startRiderServiceIfLoggedIn();
        }
    }


    @Override
    protected void onResume() {

        super.onResume();

        startRiderServiceIfLoggedIn();
    }


    private void startRiderServiceIfLoggedIn() {

        SharedPreferences prefs =
                getSharedPreferences(
                        "fai_fai_rider",
                        MODE_PRIVATE
                );

        int riderId = prefs.getInt("rider_id", 0);
        String accessToken = prefs.getString("access_token", "");

        boolean hasForegroundLocation =
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;

        // Android 14+ does not allow a location foreground service to start
        // before location permission is granted. The permission callback will
        // start it immediately after the Rider grants access.
        if (riderId > 0
                && accessToken != null
                && !accessToken.trim().isEmpty()
                && hasForegroundLocation) {
            startRiderService();
        }
    }


    private void startRiderService() {

        Intent intent =
                new Intent(
                        this,
                        RiderOrderService.class
                ).setAction(
                        RiderOrderService.ACTION_START
                );

        if (Build.VERSION.SDK_INT >= 26) {

            startForegroundService(intent);

        } else {

            startService(intent);
        }
    }


    @Override
    public boolean onKeyDown(
            int keyCode,
            KeyEvent event
    ) {

        if (keyCode == KeyEvent.KEYCODE_BACK
                && webView != null
                && webView.canGoBack()) {

            webView.goBack();
            return true;
        }

        return super.onKeyDown(
                keyCode,
                event
        );
    }


    @Override
    protected void onDestroy() {

        handler.removeCallbacks(sync);

        if (webView != null) {
            webView.destroy();
        }

        super.onDestroy();
    }


    private void clearNativeRiderSession() {
        getSharedPreferences(
                "fai_fai_rider",
                MODE_PRIVATE
        ).edit()
                .remove("rider_id")
                .remove("name")
                .remove("access_token")
                .apply();

        try {
            stopService(new Intent(this, RiderOrderService.class));
        } catch (Exception ignored) {
        }
    }


    public final class RiderBridge {

        private String decodeJavascriptResult(String raw) {
            if (raw == null || raw.trim().isEmpty() || "null".equalsIgnoreCase(raw.trim())) {
                return "";
            }

            try {
                Object decoded = new JSONTokener(raw).nextValue();
                if (decoded instanceof String) {
                    return (String) decoded;
                }
            } catch (Exception ignored) {
            }

            return raw;
        }

        @JavascriptInterface
        public void configureRider(String raw) {
            String payloadText = decodeJavascriptResult(raw);

            if (payloadText == null || payloadText.trim().isEmpty()) {
                clearNativeRiderSession();
                return;
            }

            try {
                JSONObject payload = new JSONObject(payloadText);
                String riderJson = payload.optString("rider", "").trim();
                String accessToken = payload.optString("token", "").trim();

                if (riderJson.isEmpty() || accessToken.isEmpty()) {
                    // Secure Rider sessions require both the Rider object and bearer token.
                    clearNativeRiderSession();
                    return;
                }

                JSONObject riderObject = new JSONObject(riderJson);
                int riderId = riderObject.optInt("id", 0);

                if (riderId <= 0) {
                    clearNativeRiderSession();
                    return;
                }

                getSharedPreferences(
                        "fai_fai_rider",
                        MODE_PRIVATE
                ).edit()
                        .putInt("rider_id", riderId)
                        .putString("name", riderObject.optString("name", "Rider"))
                        .putString("access_token", accessToken)
                        .apply();

                // Re-start/poke the service so it immediately picks up a new token after login.
                startRiderService();

            } catch (Exception ignored) {
                // Keep the current native session on a transient WebView/JSON error.
            }
        }
    }

}