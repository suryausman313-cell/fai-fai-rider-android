package com.faifai.rider;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Build;
import android.util.Base64;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RiderOrderService extends Service {
    public static final String ACTION_START = "com.faifai.rider.START";
    public static final String ACTION_ACCEPT = "com.faifai.rider.ACCEPT";
    public static final String ACTION_REJECT = "com.faifai.rider.REJECT";

    private static final String BASE = "https://vita-napoli-backend-usman.onrender.com/api/v1/rider";
    private static final String SETTINGS = "https://vita-napoli-backend-usman.onrender.com/api/v1/receipt-settings";
    private static final String SERVICE_CHANNEL = "rider_background_v2";
    private static final String ORDER_CHANNEL = "rider_orders_v2";
    private static final int SERVICE_NOTIFICATION_ID = 61;
    private static final int ORDER_NOTIFICATION_ID = 62;

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private MediaPlayer alarm;
    private boolean adminAlarmEnabled = true;
    private String adminAlarmAudio = "";
    private long lastSettingsCheck = 0;
    private int ringingAssignmentId = 0;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(SERVICE_NOTIFICATION_ID, backgroundNotification());
        worker.scheduleWithFixedDelay(this::poll, 0, 8, TimeUnit.SECONDS);
    }

    private void createChannels() {
        NotificationManager manager = getSystemService(NotificationManager.class);

        NotificationChannel background = new NotificationChannel(
            SERVICE_CHANNEL,
            "Rider background service",
            NotificationManager.IMPORTANCE_MIN
        );
        background.setDescription("Keeps delivery checking active");
        background.setSound(null, null);
        background.enableVibration(false);
        background.setShowBadge(false);
        manager.createNotificationChannel(background);

        NotificationChannel orders = new NotificationChannel(
            ORDER_CHANNEL,
            "Assigned delivery orders",
            NotificationManager.IMPORTANCE_HIGH
        );
        orders.setDescription("Accept or reject assigned deliveries");
        orders.setSound(null, null); // Only Admin-selected audio is used.
        orders.enableVibration(true);
        orders.setShowBadge(true);
        manager.createNotificationChannel(orders);
    }

    private Notification backgroundNotification() {
        return new NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("Fai Fai Rider is active")
            .setContentText("Background delivery checking is ON")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null &&
            (ACTION_ACCEPT.equals(intent.getAction()) || ACTION_REJECT.equals(intent.getAction()))) {
            int assignmentId = intent.getIntExtra("assignment_id", 0);
            String status = ACTION_ACCEPT.equals(intent.getAction()) ? "accepted" : "rejected";
            worker.execute(() -> updateStatus(assignmentId, status));
        }
        return START_STICKY;
    }

    private void poll() {
        refreshAdminAlarm(true);
        int riderId = getSharedPreferences("fai_fai_rider", MODE_PRIVATE)
            .getInt("rider_id", 0);
        if (riderId <= 0) {
            stopOrderAlarm();
            return;
        }

        try {
            JSONArray items = new JSONObject(request("GET", BASE + "/deliveries/" + riderId, null))
                .optJSONArray("items");
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

            if (assigned == null) stopOrderAlarm();
            else showAssignedOrder(assigned);
        } catch (Exception ignored) { }
    }

    private void refreshAdminAlarm(boolean useCache) {
        long now = System.currentTimeMillis();
        if (useCache && now - lastSettingsCheck < 15000) return;
        lastSettingsCheck = now;
        try {
            JSONObject settings = new JSONObject(request("GET", SETTINGS, null));
            adminAlarmEnabled = settings.optBoolean("rider_alarm_enabled", true);
            adminAlarmAudio = settings.optString("rider_alarm_audio", "");
            if (!adminAlarmEnabled) stopAudioOnly();
        } catch (Exception ignored) { }
    }

    private synchronized void showAssignedOrder(JSONObject order) {
        int assignmentId = order.optInt("id");
        int orderId = order.optInt("order_id");

        if (canPostNotifications()) {
            Notification notification = new NotificationCompat.Builder(this, ORDER_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("New Delivery #" + orderId)
                .setContentText("Accept or reject this delivery")
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openApp())
                .addAction(0, "ACCEPT", action(ACTION_ACCEPT, assignmentId, 1))
                .addAction(0, "REJECT", action(ACTION_REJECT, assignmentId, 2))
                .build();
            getSystemService(NotificationManager.class).notify(ORDER_NOTIFICATION_ID, notification);
        }

        if (ringingAssignmentId != assignmentId) {
            stopAudioOnly();
            ringingAssignmentId = assignmentId;
        }

        if (adminAlarmEnabled && !adminAlarmAudio.isEmpty() && alarm == null) {
            try {
                File audioFile = adminAudioFile(adminAlarmAudio);
                alarm = new MediaPlayer();
                alarm.setDataSource(audioFile.getAbsolutePath());
                alarm.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
                alarm.setLooping(true);
                alarm.prepare();
                alarm.start();
            } catch (Exception ignored) {
                stopAudioOnly();
            }
        }
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private File adminAudioFile(String dataUrl) throws IOException {
        int comma = dataUrl.indexOf(',');
        if (!dataUrl.startsWith("data:audio/") || comma < 0) {
            throw new IOException("Invalid Admin ring");
        }
        byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
        File file = new File(getCacheDir(), "rider_admin_ring.audio");
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(bytes);
        }
        return file;
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

    private void updateStatus(int assignmentId, String status) {
        try {
            request("PUT", BASE + "/deliveries/" + assignmentId + "/status",
                "{\"status\":\"" + status + "\"}");
        } catch (Exception ignored) { }
        stopOrderAlarm();
    }

    private String request(String method, String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod(method);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        InputStream input = connection.getResponseCode() < 400
            ? connection.getInputStream()
            : connection.getErrorStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
        connection.disconnect();
        return output.toString("UTF-8");
    }

    private synchronized void stopAudioOnly() {
        if (alarm != null) {
            try { alarm.stop(); } catch (Exception ignored) { }
            alarm.release();
            alarm = null;
        }
    }

    private synchronized void stopOrderAlarm() {
        getSystemService(NotificationManager.class).cancel(ORDER_NOTIFICATION_ID);
        stopAudioOnly();
        ringingAssignmentId = 0;
    }

    @Override public void onDestroy() {
        worker.shutdownNow();
        stopOrderAlarm();
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) {
        return null;
    }
}
