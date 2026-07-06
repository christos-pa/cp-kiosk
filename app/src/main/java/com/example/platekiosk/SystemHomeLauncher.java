package com.example.platekiosk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.List;

final class SystemHomeLauncher {
    private static final String ANDROID_PACKAGE = "android";

    private SystemHomeLauncher() {
    }

    static boolean open(Context context) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        PackageManager packageManager = context.getPackageManager();
        if (openFirstLauncher(context, packageManager, homeIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
            return true;
        }
        return openFirstLauncher(context, packageManager, homeIntent, PackageManager.MATCH_ALL);
    }

    private static boolean openFirstLauncher(
            Context context, PackageManager packageManager, Intent homeIntent, int flags) {
        List<ResolveInfo> homeActivities = packageManager.queryIntentActivities(homeIntent, flags);
        for (ResolveInfo homeActivity : homeActivities) {
            if (!isSystemLauncher(context, homeActivity)) {
                continue;
            }

            Intent explicitHomeIntent = new Intent(homeIntent);
            explicitHomeIntent.setComponent(
                    new ComponentName(
                            homeActivity.activityInfo.packageName,
                            homeActivity.activityInfo.name));
            context.startActivity(explicitHomeIntent);
            return true;
        }
        return false;
    }

    private static boolean isSystemLauncher(Context context, ResolveInfo homeActivity) {
        if (homeActivity.activityInfo == null) {
            return false;
        }

        String packageName = homeActivity.activityInfo.packageName;
        return packageName != null
                && !context.getPackageName().equals(packageName)
                && !ANDROID_PACKAGE.equals(packageName);
    }
}
