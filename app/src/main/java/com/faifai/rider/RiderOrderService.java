package com.faifai.rider;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
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

    private static final long LOCATION_MIN_TIME_MS = 10_000L;
    private static final float LOCATION_MIN_DISTANCE_M = 5f;

    private final ScheduledExecutorService worker =
            Executors.newSingleThreadScheduledExecutor();

    private MediaPlayer alarm;
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
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
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

    private void sendBestLastKnownLocation() {
        if (locationManager == null) return;
        try {
            Location best = null;
            Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (gps != null) best = gps;
            if (network != null && (best == null || network.getTime() > best.getTime())) {
                best = network;
            }
            if (best != null) {
                Location finalBest = best;
                worker.execute(() -> sendLocation(finalBest));
            }
        } catch (SecurityException ignored) {
        } catch (Exception ignored) {
        }
    }

    private void sendHeartbeat() {
        int riderId = riderId();
        if (riderId <= 0) return;
        try {
            request(
                    "POST",
                    BASE + "/heartbeat/" + riderId,
                    "{}"
            );
        } catch (Exception ignored) {
        }
    }

    private void sendLocation(Location location) {
        int riderId = riderId();
        if (riderId <= 0 || location == null) return;
        try {
            JSONObject body = new JSONObject();
            body.put("lat", location.getLatitude());
            body.put("lng", location.getLongitude());
            request(
                    "POST",
                    BASE + "/location/" + riderId,
                    body.toString()
            );
        } catch (Exception ignored) {
        }
    }

    private int riderId() {
        return getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
                .getInt("rider_id", 0);
    }

    private void pollOrders() {
        int riderId = riderId();
        if (riderId <= 0) {
            stopAlarm();
            return;
        }

        try {
            JSONArray items = new JSONObject(
                    request("GET", BASE + "/deliveries/" + riderId, null)
            ).optJSONArray("items");

            JSONObject assigned = null;
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject item = items.getJSONObject(index);
                    if ("assigned".equalsIgnoreCase(item.optString("status"))) {
                        assigned = item;
                        break;
                    }
                }
            }

            if (assigned == null) stopAlarm();
            else notifyOrder(assigned);
        } catch (Exception ignored) {
        }
    }

    private synchronized void notifyOrder(JSONObject orderObject) {
        int assignmentId = orderObject.optInt("id");
        int orderId = orderObject.optInt("order_id");

        Notification notification = new NotificationCompat.Builder(this, "rider_orders")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("New Delivery #" + orderId)
                .setContentText("Accept or reject this delivery")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setContentIntent(openApp())
                .addAction(0, "ACCEPT", action(ACTION_ACCEPT, assignmentId, 1))
                .addAction(0, "REJECT", action(ACTION_REJECT, assignmentId, 2))
                .build();

        getSystemService(NotificationManager.class).notify(62, notification);

        SharedPreferences prefs = getSharedPreferences("fai_fai_rider", MODE_PRIVATE);
        if (prefs.getBoolean("native_sound", true) && alarm == null) {
            try {
                String saved = prefs.getString("ringtone_uri", "");
                Uri uri = (saved == null || saved.isEmpty())
                        ? android.media.RingtoneManager.getDefaultUri(
                        android.media.RingtoneManager.TYPE_ALARM)
                        : Uri.parse(saved);

                alarm = new MediaPlayer();
                alarm.setDataSource(this, uri);
                alarm.setAudioAttributes(
                        new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build()
                );
                alarm.setLooping(true);
                alarm.prepare();
                alarm.start();
            } catch (Exception e) {
                stopAlarm();
            }
        }
    }

    private PendingIntent action(String action, int assignmentId, int requestCode) {
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
        if (assignmentId <= 0) return;
        try {
            request(
                    "PUT",
                    BASE + "/deliveries/" + assignmentId + "/status",
                    "{\"status\":\"" + status + "\"}"
            );
        } catch (Exception ignored) {
        }
        stopAlarm();
    }

    private String request(String method, String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");

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
        return output.toString("UTF-8");
    }

    private synchronized void stopAlarm() {
        getSystemService(NotificationManager.class).cancel(62);
        if (alarm != null) {
            try {
                alarm.stop();
            } catch (Exception ignored) {
            }
            alarm.release();
            alarm = null;
        }
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
