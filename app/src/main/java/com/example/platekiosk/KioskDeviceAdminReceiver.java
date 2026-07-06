package com.example.platekiosk;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;

public class KioskDeviceAdminReceiver extends DeviceAdminReceiver {
    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        if (KioskConfig.isKioskActive(context)) {
            KioskPolicy.apply(context);
        } else {
            KioskHomeComponent.setEnabled(context, false);
        }
    }
}
