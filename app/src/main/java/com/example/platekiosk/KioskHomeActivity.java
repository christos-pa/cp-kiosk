package com.example.platekiosk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;

import java.util.List;

public class KioskHomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (KioskConfig.isKioskActive(this)) {
            Intent kioskIntent = new Intent(this, MainActivity.class);
            kioskIntent.putExtra(MainActivity.EXTRA_HOME_LAUNCH, true);
            kioskIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(kioskIntent);
        } else {
            openSystemHomeScreen();
        }

        finish();
    }

    private void openSystemHomeScreen() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        PackageManager packageManager = getPackageManager();
        List<ResolveInfo> homeActivities =
                packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
        for (ResolveInfo homeActivity : homeActivities) {
            if (!getPackageName().equals(homeActivity.activityInfo.packageName)) {
                homeIntent.setComponent(
                        new ComponentName(
                                homeActivity.activityInfo.packageName,
                                homeActivity.activityInfo.name));
                startActivity(homeIntent);
                return;
            }
        }
    }
}
