package com.faifai.rider;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import android.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RiderOrderService extends Service {
    public static final String ACTION_START = "com.faifai.rider.START";
    public static final String ACTION_ACCEPT = "com.faifai.rider.ACCEPT";
    public static final String ACTION_REJECT = "com.faifai.rider.REJECT";

    // This is the same live Render backend used by the Fai Fai web app.
    private static final String BASE =
            "https://vita-napoli-backend-usman.onrender.com/api/v1/rider";

    // Admin controls the Rider ringtone from Receipt & Printer settings.
    private static final String SETTINGS_API =
            "https://vita-napoli-backend-usman.onrender.com/api/v1/receipt-settings";

    private static final long LOCATION_MIN_TIME_MS = 10_000L;
    private static final float LOCATION_MIN_DISTANCE_M = 5f;
    private static final long LOCATION_FRESHNESS_PING_SECONDS = 30L;
    private static final int ORDER_NOTIFICATION_BASE = 1000;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();

    private MediaPlayer alarm;
    private volatile boolean adminAlarmEnabled = true;
    private volatile String adminAlarmAudio = "";
    private volatile String playingAlarmAudio = "";
    private volatile long lastSettingsCheck = 0L;
    private volatile boolean authInvalid = false;
    private final Set<Integer> activeAssignmentNotifications = new HashSet<>();

    private LocationManager locationManager;
    private LocationListener locationListener;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();

        createNotificationChannels();
        startForeground(61, buildServiceNotification());
        acquireWakeLock();
        startLocationTracking();

        // Existing background order polling.
        worker.scheduleWithFixedDelay(this::pollOrders, 0, 10, TimeUnit.SECONDS);

        // Keep the backend heartbeat fresh even while WebView/screen is off.
        worker.scheduleWithFixedDelay(this::sendHeartbeat, 0, 15, TimeUnit.SECONDS);

        // Re-send the best known GPS snapshot while stationary too. Without this,
        // Android may not emit a new location callback when the Rider is standing still,
        // causing Admin to incorrectly show "GPS outdated" even though the service is alive.
        worker.scheduleWithFixedDelay(
                this::refreshLocationSnapshot,
                5,
                LOCATION_FRESHNESS_PING_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // MainActivity calls ACTION_START after login/token refresh.
        if (intent == null || ACTION_START.equals(intent.getAction())) {
            authInvalid = false;
        }

        if (intent != null
                && (ACTION_ACCEPT.equals(intent.getAction())
                || ACTION_REJECT.equals(intent.getAction()))) {
            int assignmentId = intent.getIntExtra("assignment_id", 0);
            String status = ACTION_ACCEPT.equals(intent.getAction())
                    ? "accepted"
                    : "rejected";
            worker.execute(() -> updateAssignment(assignmentId, status));
        } else {
            // Permission might have been granted after the service first started.
            startLocationTracking();
            worker.execute(this::sendHeartbeat);
            worker.execute(this::refreshLocationSnapshot);
        }
        return START_STICKY;
    }

    private void createNotificationChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel active = new NotificationChannel(
                "rider_active",
                "Rider background service",
                NotificationManager.IMPORTANCE_LOW
        );
        active.setSound(null, null);
        manager.createNotificationChannel(active);

        NotificationChannel orders = new NotificationChannel(
                "rider_orders",
                "New delivery orders",
                NotificationManager.IMPORTANCE_HIGH
        );
        orders.setSound(null, null);
        orders.enableVibration(true);
        manager.createNotificationChannel(orders);
    }

    private Notification buildServiceNotification() {
        return new NotificationCompat.Builder(this, "rider_active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Fai Fai Rider active")
                .setContentText("Live location + delivery orders running")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = manager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "FaiFaiRider:BackgroundWorker"
            );
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception ignored) {
        }
    }

    private synchronized void startLocationTracking() {
        if (locationManager != null && locationListener != null) return;

        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (!fine && !coarse) return;

        try {
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
            locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location location) {
                    if (location != null) {
                        worker.execute(() -> sendLocation(location));
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {
                }

                @Override
                public void onProviderEnabled(String provider) {
                    sendBestLastKnownLocation();
                }

                @Override
                public void onProviderDisabled(String provider) {
                }
            };

            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE_M,
                        locationListener
                );
            }

            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        LOCATION_MIN_TIME_MS,
                        LOCATION_MIN_DISTANCE_M,
                        locationListener
                );
            }

            sendBestLastKnownLocation();
        } catch (SecurityException ignored) {
            locationListener = null;
            locationManager = null;
        } catch (Exception ignored) {
            locationListener = null;
            locationManager = null;
        }
    }

    private Location bestLastKnownLocation() {
        if (locationManager == null) return null;
        try {
            Location best = null;
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null) best = gps;
            if (network != null && (best == null || network.getTime() > best.getTime())) {
                best = network;
            }
            return best;
        } catch (SecurityException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void sendBestLastKnownLocation() {
        Location best = bestLastKnownLocation();
        if (best != null) {
            Location finalBest = best;
            worker.execute(() -> sendLocation(finalBest));
        }
    }

    private void refreshLocationSnapshot() {
        if (!hasSecureSession() || authInvalid) return;
        Location best = bestLastKnownLocation();
        if (best != null) {
            // The backend stores its own current timestamp when this authenticated
            // GPS snapshot arrives, so a stationary Rider remains GPS-fresh.
            sendLocation(best);
        }
    }

    private void sendHeartbeat() {
        int riderId = riderId();
        if (riderId <= 0 || !hasSecureSession() || authInvalid) return;
        try {
            request(
                    "POST",
                    BASE + "/heartbeat/" + riderId,
                    "{}"
            );
        } catch (HttpStatusException e) {
            handleHttpFailure(e);
        } catch (Exception ignored) {
        }
    }

    private void sendLocation(Location location) {
        int riderId = riderId();
        if (riderId <= 0 || location == null || !hasSecureSession() || authInvalid) return;
        try {
            JSONObject body = new JSONObject();
            body.put("lat", location.getLatitude());
            body.put("lng", location.getLongitude());
            request(
                    "POST",
                    BASE + "/location/" + riderId,
                    body.toString()
            );
        } catch (HttpStatusException e) {
            handleHttpFailure(e);
        } catch (Exception ignored) {
        }
    }

    private int riderId() {
        return getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                .getInt("rider_id", 0);
    }

    private String riderToken() {
        String token = getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                .getString("access_token", "");
        return token == null ? "" : token.trim();
    }

    private boolean hasSecureSession() {
        return riderId() > 0 && !riderToken().isEmpty();
    }

    private void pollOrders() {
        refreshAdminAlarm();

        int riderId = riderId();
        if (riderId <= 0 || !hasSecureSession() || authInvalid) {
            stopAlarm();
            return;
        }

        try {
            JSONArray items = new JSONObject(
                    request("GET", BASE + "/deliveries/" + riderId, null)
            ).optJSONArray("items");

            Set<Integer> currentAssigned = new HashSet<>();

            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.getJSONObject(index);
                    if (!"assigned".equalsIgnoreCase(item.optString("status"))) {
                        continue;
                    }

                    int assignmentId = item.optInt("id");
                    if (assignmentId <= 0) continue;

                    currentAssigned.add(assignmentId);
                    notifyOrder(item);
                }
            }

            syncAssignmentNotifications(currentAssigned);

            if (currentAssigned.isEmpty()) {
                stopAudioOnly();
            } else {
                ensureAdminAlarmPlaying();
            }

        } catch (HttpStatusException e) {
            handleHttpFailure(e);
        } catch (Exception ignored) {
        }
    }

    private int orderNotificationId(int assignmentId) {
        return ORDER_NOTIFICATION_BASE + Math.abs(assignmentId % 1_000_000);
    }

    private synchronized void notifyOrder(JSONObject orderObject) {
        int assignmentId = orderObject.optInt("id");
        int orderId = orderObject.optInt("order_id");
        if (assignmentId <= 0) return;

        Notification notification = new NotificationCompat.Builder(this, "rider_orders")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("New Delivery #" + orderId)
                .setContentText("Accept or reject this delivery")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .addAction(0, "ACCEPT", action(ACTION_ACCEPT, assignmentId))
                .addAction(0, "REJECT", action(ACTION_REJECT, assignmentId))
                .build();

        getSystemService(NotificationManager.class)
                .notify(orderNotificationId(assignmentId), notification);
    }

    private synchronized void syncAssignmentNotifications(Set<Integer> currentAssigned) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        for (Integer previous : new HashSet<>(activeAssignmentNotifications)) {
            if (!currentAssigned.contains(previous)) {
                manager.cancel(orderNotificationId(previous));
            }
        }

        activeAssignmentNotifications.clear();
        activeAssignmentNotifications.addAll(currentAssigned);
    }

    private synchronized void ensureAdminAlarmPlaying() {
        // No local/default ringtone is used. Only the ringtone selected by Admin plays.
        if (!adminAlarmEnabled
                || adminAlarmAudio == null
                || adminAlarmAudio.isEmpty()) {
            stopAudioOnly();
            return;
        }

        if (alarm != null && adminAlarmAudio.equals(playingAlarmAudio)) {
            return;
        }

        stopAudioOnly();

        try {
            File audioFile = adminAudioFile(adminAlarmAudio, "rider_admin_ring");
            alarm = new MediaPlayer();
            alarm.setDataSource(audioFile.getAbsolutePath());
            alarm.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .build()
            );
            alarm.setLooping(true);
            alarm.prepare();
            alarm.start();
            playingAlarmAudio = adminAlarmAudio;
        } catch (Exception e) {
            stopAudioOnly();
        }
    }

    private void refreshAdminAlarm() {
        long now = System.currentTimeMillis();
        if (now - lastSettingsCheck < 60_000L) return;
        lastSettingsCheck = now;

        try {
            JSONObject settings = new JSONObject(
                    request("GET", SETTINGS_API, null)
            );

            boolean enabled = settings.optBoolean("rider_alarm_enabled", true);
            String audio = settings.optString("rider_alarm_audio", "");
            if (audio == null) audio = "";

            boolean soundChanged = !audio.equals(adminAlarmAudio);
            adminAlarmEnabled = enabled;
            adminAlarmAudio = audio;

            // Admin turned Rider ring OFF or changed the selected sound.
            // Stop only audio here; keep the delivery notification visible.
            if (!adminAlarmEnabled || soundChanged) {
                stopAudioOnly();
            }
        } catch (Exception ignored) {
            // Keep the last successfully loaded Admin setting.
        }
    }

    private File adminAudioFile(String dataUrl, String name) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:audio/") || comma < 0) {
            throw new IOException("Invalid Admin Rider ring");
        }

        byte[] bytes = Base64.decode(
                dataUrl.substring(comma + 1),
                Base64.DEFAULT
        );

        File file = new File(getCacheDir(), name + ".audio");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
        }
        return file;
    }

    private synchronized void stopAudioOnly() {
        if (alarm != null) {
            try {
                alarm.stop();
            } catch (Exception ignored) {
            }
            alarm.release();
            alarm = null;
        }
        playingAlarmAudio = "";
    }

    private PendingIntent action(String action, int assignmentId) {
        // PendingIntent identity ignores extras, so every assignment must use a
        // unique request code. Otherwise multiple notifications can Accept/Reject
        // the wrong (most recently assigned) order.
        int actionOffset = ACTION_ACCEPT.equals(action) ? 1 : 2;
        int requestCode = Math.abs((assignmentId * 10) + actionOffset);
        return PendingIntent.getService(
                this,
                requestCode,
                new Intent(this, RiderOrderService.class)
                        .setAction(action)
                        .putExtra("assignment_id", assignmentId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(
                this,
                3,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private void updateAssignment(int assignmentId, String status) {
        if (assignmentId <= 0 || !hasSecureSession() || authInvalid) return;
        try {
            JSONObject body = new JSONObject();
            body.put("status", status);
            if ("rejected".equalsIgnoreCase(status)) {
                // Quick Reject from the Android notification cannot open a text box.
                // The Admin still receives a clear rejection reason.
                body.put("reason", "Rejected from Rider Android notification");
            }

            request(
                    "PUT",
                    BASE + "/deliveries/" + assignmentId + "/status",
                    body.toString()
            );

            getSystemService(NotificationManager.class)
                    .cancel(orderNotificationId(assignmentId));
            synchronized (this) {
                activeAssignmentNotifications.remove(assignmentId);
            }

            // Re-check immediately: if another assignment is still waiting,
            // keep its notification and Admin-selected ring active.
            pollOrders();

        } catch (HttpStatusException e) {
            handleHttpFailure(e);
        } catch (Exception ignored) {
        }
    }

    private void handleHttpFailure(HttpStatusException error) {
        if (error.code == 401 || error.code == 403) {
            authInvalid = true;
            stopAlarm();
            showLoginRequiredForegroundNotification();
        }
    }

    private void showLoginRequiredForegroundNotification() {
        Notification notification = new NotificationCompat.Builder(this, "rider_active")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Fai Fai Rider login required")
                .setContentText("Open Rider app and login again to resume live tracking")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openApp())
                .build();
        getSystemService(NotificationManager.class).notify(61, notification);
    }

    private String request(String method, String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");

        String token = riderToken();
        if (url.startsWith(BASE) && !token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }

        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }

        int code = connection.getResponseCode();
        InputStream input = code < 400
                ? connection.getInputStream()
                : connection.getErrorStream();

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (input != null) {
            byte[] buffer = new byte[2048];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
            }
            input.close();
        }
        connection.disconnect();

        String responseBody = output.toString("UTF-8");
        if (code >= 400) {
            throw new HttpStatusException(code, responseBody);
        }

        if (url.startsWith(BASE)) {
            authInvalid = false;
        }
        return responseBody;
    }

    private static final class HttpStatusException extends IOException {
        final int code;

        HttpStatusException(int code, String body) {
            super("HTTP " + code + ": " + (body == null ? "" : body));
            this.code = code;
        }
    }

    private synchronized void stopAlarm() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        for (Integer assignmentId : new HashSet<>(activeAssignmentNotifications)) {
            manager.cancel(orderNotificationId(assignmentId));
        }
        activeAssignmentNotifications.clear();
        stopAudioOnly();
    }

    private synchronized void stopLocationTracking() {
        if (locationManager != null && locationListener != null) {
            try {
                locationManager.removeUpdates(locationListener);
            } catch (SecurityException ignored) {
            } catch (Exception ignored) {
            }
        }
        locationListener = null;
        locationManager = null;
    }

    @Override
    public void onDestroy() {
        worker.shutdownNow();
        stopLocationTracking();
        stopAlarm();
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
