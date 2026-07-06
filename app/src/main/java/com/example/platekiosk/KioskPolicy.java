package com.example.platekiosk;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

public final class KioskPolicy {
    private static final String[] REMOTE_CONTROL_PACKAGES = {
            "com.splashtop.streamer.csrs"
    };

    private KioskPolicy() {
    }

    public static boolean apply(Context context) {
        DevicePolicyManager policyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        String packageName = context.getPackageName();

        if (!policyManager.isDeviceOwnerApp(packageName)) {
            return false;
        }

        ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);
        ComponentName kioskActivity = new ComponentName(context, KioskHomeActivity.class);

        KioskHomeComponent.setEnabled(context, true);
        policyManager.setLockTaskPackages(admin, lockTaskPackages(context, packageName));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            policyManager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
        }

        IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
        homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
        homeFilter.addCategory(Intent.CATEGORY_HOME);
        policyManager.addPersistentPreferredActivity(admin, homeFilter, kioskActivity);

        policyManager.setKeyguardDisabled(admin, true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            policyManager.setStatusBarDisabled(admin, true);
        }

        policyManager.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
        policyManager.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
        policyManager.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);

        policyManager.setGlobalSetting(
                admin,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                Integer.toString(
                        BatteryManager.BATTERY_PLUGGED_AC
                                | BatteryManager.BATTERY_PLUGGED_USB
                                | BatteryManager.BATTERY_PLUGGED_WIRELESS));

        return true;
    }

    public static boolean release(Context context) {
        DevicePolicyManager policyManager =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        String packageName = context.getPackageName();

        if (!policyManager.isDeviceOwnerApp(packageName)) {
            return false;
        }

        ComponentName admin = new ComponentName(context, KioskDeviceAdminReceiver.class);
        policyManager.setLockTaskPackages(admin, new String[]{});
        policyManager.clearPackagePersistentPreferredActivities(admin, packageName);
        policyManager.setKeyguardDisabled(admin, false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            policyManager.setStatusBarDisabled(admin, false);
        }
        policyManager.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
        policyManager.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
        policyManager.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
        policyManager.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, "0");
        return true;
    }

    private static String[] lockTaskPackages(Context context, String packageName) {
        List<String> packages = new ArrayList<>();
        packages.add(packageName);

        PackageManager packageManager = context.getPackageManager();
        for (String remoteControlPackage : REMOTE_CONTROL_PACKAGES) {
            try {
                packageManager.getPackageInfo(remoteControlPackage, 0);
                packages.add(remoteControlPackage);
            } catch (PackageManager.NameNotFoundException ignored) {
                // Remote support app is optional.
            }
        }

        return packages.toArray(new String[0]);
    }
}
