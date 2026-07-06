package com.example.platekiosk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

final class KioskHomeComponent {
    private KioskHomeComponent() {
    }

    static void setEnabled(Context context, boolean enabled) {
        PackageManager packageManager = context.getPackageManager();
        ComponentName homeComponent = new ComponentName(context, KioskHomeActivity.class);
        int newState =
                enabled
                        ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;

        if (packageManager.getComponentEnabledSetting(homeComponent) == newState) {
            return;
        }

        packageManager.setComponentEnabledSetting(
                homeComponent,
                newState,
                PackageManager.DONT_KILL_APP);
    }

    static boolean isEnabled(Context context) {
        PackageManager packageManager = context.getPackageManager();
        ComponentName homeComponent = new ComponentName(context, KioskHomeActivity.class);
        int state = packageManager.getComponentEnabledSetting(homeComponent);
        return state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
    }
}
