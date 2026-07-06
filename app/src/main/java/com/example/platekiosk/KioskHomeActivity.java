package com.example.platekiosk;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

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
            SystemHomeLauncher.open(this);
        }

        finish();
    }
}
