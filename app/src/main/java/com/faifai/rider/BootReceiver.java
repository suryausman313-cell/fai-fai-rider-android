package com.faifai.rider;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * Restarts the Rider foreground service after a normal phone reboot when the
 * Rider was already logged in and background location permission is available.
 * If Android/OEM refuses the background start, opening the Rider app will start
 * the service again from MainActivity.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences(
                "fai_fai_rider",
                Context.MODE_PRIVATE
        );

        int riderId = prefs.getInt("rider_id", 0);
        String token = prefs.getString("access_token", "");
        if (riderId <= 0 || token == null || token.trim().isEmpty()) return;

        boolean foregroundLocation =
                context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
        if (!foregroundLocation) return;

        // Starting a location foreground service from the background on modern
        // Android requires background location access.
        if (Build.VERSION.SDK_INT >= 29
                && context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        try {
            Intent serviceIntent = new Intent(context, RiderOrderService.class)
                    .setAction(RiderOrderService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception ignored) {
            // Some manufacturers add extra battery/background restrictions.
            // The next Rider app open starts the service again.
        }
    }
}
