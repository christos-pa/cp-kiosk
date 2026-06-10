package com.example.platekiosk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!KioskConfig.isKioskActive(context)) {
            return;
        }

        Intent watchdogIntent = new Intent(context, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(watchdogIntent);
        } else {
            context.startService(watchdogIntent);
        }

        Intent kioskIntent = new Intent(context, MainActivity.class);
        kioskIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(kioskIntent);
    }
}
