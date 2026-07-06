package com.example.platekiosk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    static final String EXTRA_HOME_LAUNCH = "com.example.platekiosk.extra.HOME_LAUNCH";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 100;
    private static final int MEDIA_PERMISSION_REQUEST = 101;
    private static final int EDGE_WIDTH_DP = 72;
    private static final int SWIPE_DISTANCE_DP = 180;
    private static final long COOKIE_FLUSH_INTERVAL_MS = 5 * 60_000L;
    private static final long[] SYSTEM_BAR_REHIDE_DELAYS_MS = {0L, 80L, 250L, 750L, 1_500L};

    private WebView webView;
    private ProgressBar progressBar;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private float touchStartX;
    private float touchStartY;
    private boolean adminDialogVisible;
    private boolean networkWasLost;
    private boolean screenPinningAttempted;
    private boolean temporarySystemNavigation;
    private PermissionRequest pendingMediaPermissionRequest;

    private final Runnable autoReloadTask = new Runnable() {
        @Override
        public void run() {
            reloadCurrentPage();
            refreshAutoReloadTimer();
        }
    };

    private final Runnable errorReloadTask = this::reloadCurrentPage;
    private final Runnable cookieFlushTask = new Runnable() {
        @Override
        public void run() {
            flushCookies();
            refreshCookieFlushTimer();
        }
    };
    private final Runnable systemBarRehideTask = () -> {
        if (KioskConfig.isKioskActive(this) && !adminDialogVisible) {
            enterImmersiveMode();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdgeWindow();
        setContentView(R.layout.activity_main);

        if (KioskConfig.isKioskActive(this) && isHomeLaunch(getIntent())) {
            screenPinningAttempted = false;
        }
        if (!KioskConfig.isKioskActive(this) && isHomeLaunch(getIntent())) {
            openSystemHomeScreen();
            finish();
            return;
        }

        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);

        configureWebView();
        configureAdminGesture();
        configureSystemBarRehide();
        requestNotificationPermission();
        startWatchdog();
        applyKioskPolicyIfActive();

        if (savedInstanceState == null) {
            loadConfiguredUrl();
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerNetworkCallback();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (KioskConfig.isKioskActive(this) && isHomeLaunch(intent)) {
            screenPinningAttempted = false;
        }
        if (!KioskConfig.isKioskActive(this) && isHomeLaunch(intent)) {
            openSystemHomeScreen();
            finish();
            return;
        }
        applyKioskPolicyIfActive();
        scheduleSystemBarRehide();
        if (KioskConfig.isKioskActive(this)) {
            enterLockTaskMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyKioskPolicyIfActive();
        applyBrowserSettings();
        applyRuntimeSettings();
        refreshAutoReloadTimer();
        refreshCookieFlushTimer();
        scheduleSystemBarRehide();
        if (KioskConfig.isKioskActive(this)) {
            temporarySystemNavigation = false;
            enterLockTaskMode();
            scheduleLockTaskRecheck();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        if (KioskConfig.isKioskActive(this) && !adminDialogVisible && !temporarySystemNavigation) {
            screenPinningAttempted = false;
            enterLockTaskMode();
        }
        super.onUserLeaveHint();
    }

    @Override
    protected void onPause() {
        flushCookiesIfReady();
        handler.removeCallbacks(autoReloadTask);
        handler.removeCallbacks(errorReloadTask);
        handler.removeCallbacks(cookieFlushTask);
        handler.removeCallbacks(systemBarRehideTask);
        super.onPause();
    }

    @Override
    protected void onStop() {
        flushCookiesIfReady();
        unregisterNetworkCallback();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        flushCookiesIfReady();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Back navigation is intentionally disabled while this is the kiosk home app.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && !adminDialogVisible) {
            scheduleSystemBarRehide();
            if (KioskConfig.isKioskActive(this)) {
                scheduleLockTaskRecheck();
            }
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (!adminDialogVisible) {
            scheduleSystemBarRehide();
        }
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        applyBrowserSettings();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                flushCookies();
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error) {
                if (request.isForMainFrame() && KioskConfig.reloadOnError(MainActivity.this)) {
                    scheduleErrorReload();
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isAllowedUrl(request.getUrl().toString());
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String[] requestedResources = getAllowedMediaResources(request);
                if (requestedResources.length == 0) {
                    request.deny();
                    return;
                }

                String[] missingPermissions = getMissingAndroidPermissions(requestedResources);
                if (missingPermissions.length == 0) {
                    request.grant(requestedResources);
                    return;
                }

                if (pendingMediaPermissionRequest != null) {
                    pendingMediaPermissionRequest.deny();
                }
                pendingMediaPermissionRequest = request;
                requestPermissions(missingPermissions, MEDIA_PERMISSION_REQUEST);
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (request == pendingMediaPermissionRequest) {
                    pendingMediaPermissionRequest = null;
                }
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MEDIA_PERMISSION_REQUEST || pendingMediaPermissionRequest == null) {
            return;
        }

        PermissionRequest request = pendingMediaPermissionRequest;
        pendingMediaPermissionRequest = null;
        String[] allowedResources = getAllowedMediaResources(request);
        if (getMissingAndroidPermissions(allowedResources).length > 0) {
            request.deny();
            return;
        }
        request.grant(allowedResources);
    }

    private String[] getAllowedMediaResources(PermissionRequest request) {
        List<String> allowedResources = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                allowedResources.add(resource);
            }
        }
        return allowedResources.toArray(new String[0]);
    }

    private String[] getMissingAndroidPermissions(String[] requestedResources) {
        List<String> missingPermissions = new ArrayList<>();
        for (String resource : requestedResources) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)
                    && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                            != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        return missingPermissions.toArray(new String[0]);
    }

    private void configureAdminGesture() {
        webView.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                touchStartX = event.getX();
                touchStartY = event.getY();
            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                float density = getResources().getDisplayMetrics().density;
                float deltaX = event.getX() - touchStartX;
                float deltaY = Math.abs(event.getY() - touchStartY);
                if (touchStartX <= EDGE_WIDTH_DP * density
                        && deltaX >= SWIPE_DISTANCE_DP * density
                        && deltaX > deltaY * 1.5f) {
                    if (KioskConfig.isKioskActive(this)) {
                        showPinPrompt();
                    } else {
                        showAdminMenu();
                    }
                }
                scheduleSystemBarRehide();
            }
            return false;
        });
    }

    private void showPinPrompt() {
        if (adminDialogVisible) {
            return;
        }

        adminDialogVisible = true;
        StringBuilder pinValue = new StringBuilder();
        TextView pinDisplay = createPinDisplay();
        TextView errorText = createPinErrorText();
        LinearLayout panel = createDialogPanel();
        panel.addView(createHelperText(R.string.admin_unlock_message), matchWidth());
        panel.addView(pinDisplay, spacedMatchWidth(dp(14)));
        panel.addView(errorText, spacedMatchWidth(dp(4)));
        panel.addView(createPinKeypad(pinValue, pinDisplay, errorText), spacedMatchWidth(dp(8)));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.admin_unlock_title)
                .setView(panel)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.unlock_button, null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (KioskConfig.verifyPin(this, pinValue.toString())) {
                        dialog.dismiss();
                        webView.post(this::showAdminMenu);
                    } else {
                        pinValue.setLength(0);
                        updatePinDisplay(pinDisplay, pinValue);
                        errorText.setText(R.string.pin_incorrect);
                    }
                });
        });
        dialog.setOnDismissListener(ignored -> onAdminDialogDismissed());
        dialog.show();
    }

    private void showAdminMenu() {
        adminDialogVisible = true;
        final AlertDialog[] dialogHolder = new AlertDialog[1];
        LinearLayout menu = createDialogPanel();
        menu.addView(createDialogHeadline(R.string.admin_menu_title), matchWidth());
        menu.addView(createKioskActiveSwitch(), spacedAdminMenuRowParams(dp(8)));
        menu.addView(createHomeAppSettingsRow(dialogHolder), adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(dialogHolder, R.string.admin_reload, this::reloadCurrentPage),
                adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(dialogHolder, R.string.admin_home, this::loadConfiguredUrl),
                adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(
                        dialogHolder,
                        R.string.admin_clear_browser_data,
                        () -> webView.post(this::showClearBrowserDataConfirmation)),
                adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(dialogHolder, R.string.admin_back, this::goBack),
                adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(dialogHolder, R.string.admin_forward, this::goForward),
                adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(
                        dialogHolder,
                        R.string.admin_settings,
                        () -> webView.post(this::showSettings)),
                adminMenuRowParams());
        menu.addView(
                createAdminMenuRow(
                        dialogHolder,
                        R.string.admin_exit,
                        () -> webView.post(this::showExitConfirmation)),
                adminMenuRowParams());
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(menu)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialogHolder[0] = dialog;
        dialog.setOnDismissListener(ignored -> onAdminDialogDismissed());
        dialog.show();
    }

    private TextView createDialogHeadline(int text) {
        TextView headline = new TextView(this);
        headline.setText(text);
        headline.setTextColor(Color.rgb(18, 28, 42));
        headline.setTextSize(22);
        headline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        headline.setPadding(0, dp(2), 0, dp(4));
        return headline;
    }

    private Switch createKioskActiveSwitch() {
        Switch kioskSwitch = new Switch(this);
        kioskSwitch.setText(R.string.kiosk_active_switch);
        kioskSwitch.setTextSize(17);
        kioskSwitch.setGravity(Gravity.CENTER_VERTICAL);
        kioskSwitch.setPadding(0, 0, 0, 0);
        kioskSwitch.setChecked(KioskConfig.isKioskActive(this));
        kioskSwitch.setOnCheckedChangeListener(
                (button, enabled) -> {
                    if (enabled == KioskConfig.isKioskActive(this)) {
                        return;
                    }
                    if (enabled) {
                        setKioskEnabled(true);
                    } else {
                        disableKiosk();
                    }
                });
        return kioskSwitch;
    }

    private LinearLayout createHomeAppSettingsRow(AlertDialog[] dialogHolder) {
        boolean homeAppEnabled = isKioskHomeAppEnabled();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);

        TextView label = new TextView(this);
        label.setText(R.string.admin_home_app);
        label.setTextColor(Color.rgb(18, 28, 42));
        label.setTextSize(17);
        label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(
                label,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        1f));

        TextView status = new TextView(this);
        status.setText(
                homeAppEnabled
                        ? getString(R.string.home_app_configured)
                        : getString(R.string.home_app_configure));
        status.setTextColor(
                homeAppEnabled
                        ? Color.rgb(25, 135, 84)
                        : Color.rgb(139, 30, 45));
        status.setTextSize(16);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        status.setContentDescription(
                getString(
                        homeAppEnabled
                                ? R.string.home_app_enabled
                                : R.string.home_app_not_enabled));
        row.addView(
                status,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        row.setOnClickListener(
                view -> {
                    if (isDeviceOwner()) {
                        Toast.makeText(
                                        this,
                                        R.string.home_app_managed_by_strong_kiosk,
                                        Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                    openHomeAppSettings();
                });
        return row;
    }

    private TextView createAdminMenuRow(
            AlertDialog[] dialogHolder, int text, Runnable action) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextColor(Color.rgb(18, 28, 42));
        row.setTextSize(17);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 0, 0, 0);
        row.setOnClickListener(
                view -> {
                    if (dialogHolder[0] != null) {
                        dialogHolder[0].dismiss();
                    }
                    action.run();
                });
        return row;
    }

    private LinearLayout.LayoutParams adminMenuRowParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52));
    }

    private LinearLayout.LayoutParams spacedAdminMenuRowParams(int topMargin) {
        LinearLayout.LayoutParams params = adminMenuRowParams();
        params.topMargin = topMargin;
        return params;
    }

    private boolean isKioskHomeAppEnabled() {
        if (isDeviceOwner()) {
            return true;
        }
        ComponentName kioskHome = new ComponentName(this, KioskHomeActivity.class);
        List<IntentFilter> filters = new ArrayList<>();
        List<ComponentName> activities = new ArrayList<>();
        getPackageManager().getPreferredActivities(filters, activities, getPackageName());
        return activities.contains(kioskHome);
    }

    private boolean isDeviceOwner() {
        DevicePolicyManager policyManager =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        return policyManager.isDeviceOwnerApp(getPackageName());
    }

    private void openHomeAppSettings() {
        screenPinningAttempted = false;
        temporarySystemNavigation = true;
        try {
            stopLockTask();
        } catch (IllegalStateException ignored) {
            // Screen pinning was not active.
        }

        Intent settingsIntent = new Intent(Settings.ACTION_HOME_SETTINGS);
        try {
            startActivity(settingsIntent);
        } catch (RuntimeException exception) {
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        }
    }

    private void showSettings() {
        adminDialogVisible = true;
        LinearLayout form = createDialogPanel();

        form.addView(createSectionLabel(R.string.website_section), matchWidth());
        EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.url_hint);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setText(KioskConfig.getUrl(this));
        styleTextInput(urlInput);
        form.addView(urlInput, spacedMatchWidth(dp(8)));

        form.addView(createSectionLabel(R.string.security_section), spacedMatchWidth(dp(18)));
        form.addView(createHelperText(R.string.current_pin_label), spacedMatchWidth(dp(8)));
        EditText pinInput = createPinInput();
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pinInput.setTextSize(17);
        pinInput.setText(KioskConfig.getPinForSettings(this));
        form.addView(pinInput, spacedMatchWidth(dp(8)));

        form.addView(createSectionLabel(R.string.display_section), spacedMatchWidth(dp(18)));
        CheckBox keepScreenOnInput =
                addCheckBox(form, R.string.keep_screen_on, KioskConfig.keepScreenOn(this));

        form.addView(createSectionLabel(R.string.browser_section), spacedMatchWidth(dp(18)));
        CheckBox thirdPartyCookiesInput =
                addCheckBox(
                        form,
                        R.string.third_party_cookies,
                        KioskConfig.allowThirdPartyCookies(this));
        CheckBox reloadOnErrorInput =
                addCheckBox(form, R.string.reload_on_error, KioskConfig.reloadOnError(this));
        CheckBox reloadOnReconnectInput =
                addCheckBox(
                        form,
                        R.string.reload_on_reconnect,
                        KioskConfig.reloadOnReconnect(this));

        EditText errorReloadDelayInput =
                createNumberInput(
                        R.string.error_reload_delay_hint,
                        KioskConfig.getErrorReloadDelaySeconds(this));
        form.addView(createHelperText(R.string.error_reload_delay_label), spacedMatchWidth(dp(10)));
        form.addView(errorReloadDelayInput, spacedMatchWidth(dp(2)));

        EditText autoReloadMinutesInput =
                createNumberInput(
                        R.string.auto_reload_minutes_hint,
                        KioskConfig.getAutoReloadMinutes(this));
        form.addView(createHelperText(R.string.auto_reload_minutes_label), spacedMatchWidth(dp(10)));
        form.addView(autoReloadMinutesInput, spacedMatchWidth(dp(2)));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(scrollView)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save_button, null)
                .create();

        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    String normalizedUrl = normalizeUrl(urlInput.getText().toString());
                    String newPin = pinInput.getText().toString();
                    Integer errorReloadDelay = parseNumber(errorReloadDelayInput);
                    Integer autoReloadMinutes = parseNumber(autoReloadMinutesInput);
                    if (!isAllowedUrl(normalizedUrl)) {
                        urlInput.setError(getString(R.string.url_invalid));
                        return;
                    }
                    if (!newPin.isEmpty() && newPin.length() != 4) {
                        pinInput.setError(getString(R.string.pin_invalid));
                        return;
                    }
                    if (!isValidNumber(errorReloadDelay)) {
                        errorReloadDelayInput.setError(getString(R.string.number_invalid));
                        return;
                    }
                    if (!isValidNumber(autoReloadMinutes)) {
                        autoReloadMinutesInput.setError(getString(R.string.number_invalid));
                        return;
                    }

                    KioskConfig.setUrl(this, normalizedUrl);
                    if (!newPin.isEmpty()) {
                        KioskConfig.setPin(this, newPin);
                    }
                    KioskConfig.setKeepScreenOn(this, keepScreenOnInput.isChecked());
                    KioskConfig.setAllowThirdPartyCookies(
                            this,
                            thirdPartyCookiesInput.isChecked());
                    KioskConfig.setReloadOnError(this, reloadOnErrorInput.isChecked());
                    KioskConfig.setReloadOnReconnect(this, reloadOnReconnectInput.isChecked());
                    KioskConfig.setErrorReloadDelaySeconds(this, errorReloadDelay);
                    KioskConfig.setAutoReloadMinutes(this, autoReloadMinutes);
                    applyBrowserSettings();
                    applyRuntimeSettings();
                    refreshAutoReloadTimer();
                    dialog.dismiss();
                    loadConfiguredUrl();
                    Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                }));
        dialog.setOnDismissListener(ignored -> onAdminDialogDismissed());
        dialog.show();
    }

    private void showExitConfirmation() {
        adminDialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.exit_title)
                .setMessage(R.string.exit_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.exit_button, (ignored, which) -> exitKioskTemporarily())
                .create();
        dialog.setOnDismissListener(ignored -> onAdminDialogDismissed());
        dialog.show();
    }

    private void showClearBrowserDataConfirmation() {
        adminDialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.clear_browser_data_title)
                .setMessage(R.string.clear_browser_data_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(
                        R.string.clear_browser_data_button,
                        (ignored, which) -> clearBrowserData())
                .create();
        dialog.setOnDismissListener(ignored -> onAdminDialogDismissed());
        dialog.show();
    }

    private void clearBrowserData() {
        webView.stopLoading();
        webView.clearCache(true);
        webView.clearHistory();
        webView.clearFormData();
        WebStorage.getInstance().deleteAllData();

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(
                ignored -> {
                    cookieManager.flush();
                    runOnUiThread(
                            () -> {
                                loadConfiguredUrl();
                                Toast.makeText(
                                                this,
                                                R.string.browser_data_cleared,
                                                Toast.LENGTH_SHORT)
                                        .show();
                            });
                });
    }

    private void disableKiosk() {
        setKioskEnabled(false);
    }

    private void exitKioskTemporarily() {
        screenPinningAttempted = false;
        temporarySystemNavigation = true;
        try {
            stopLockTask();
        } catch (IllegalStateException ignored) {
            // Preview mode may not have started lock-task mode.
        }
        if (!isDeviceOwner()) {
            KioskPolicy.release(this);
        }
        openSystemHomeScreen();
    }

    private void setKioskEnabled(boolean enabled) {
        KioskConfig.setKioskActive(this, enabled);
        if (enabled) {
            boolean requestingHomeRole = requestHomeRoleIfNeeded();
            KioskPolicy.apply(this);
            applyRuntimeSettings();
            scheduleSystemBarRehide();
            if (!requestingHomeRole) {
                enterLockTaskMode();
            }
            Toast.makeText(this, R.string.kiosk_enabled, Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            stopLockTask();
        } catch (IllegalStateException ignored) {
            // Preview mode may not have started lock-task mode.
        }
        KioskPolicy.release(this);
        handler.removeCallbacks(systemBarRehideTask);
        Toast.makeText(this, R.string.kiosk_disabled, Toast.LENGTH_SHORT).show();
    }

    private boolean requestHomeRoleIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }

        DevicePolicyManager policyManager =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (policyManager.isDeviceOwnerApp(getPackageName())) {
            return false;
        }

        RoleManager roleManager = (RoleManager) getSystemService(Context.ROLE_SERVICE);
        if (roleManager == null
                || !roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
                || roleManager.isRoleHeld(RoleManager.ROLE_HOME)) {
            return false;
        }

        startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME));
        return true;
    }

    private void openSystemHomeScreen() {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

        List<ResolveInfo> homeActivities =
                getPackageManager().queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY);
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

        homeIntent.setComponent(null);
        startActivity(homeIntent);
    }

    private void loadConfiguredUrl() {
        String url = KioskConfig.getUrl(this);
        if (isAllowedUrl(url)) {
            webView.loadUrl(url);
        } else {
            webView.loadDataWithBaseURL(
                    null,
                    getString(R.string.no_url_html),
                    "text/html",
                    "UTF-8",
                    null);
        }
    }

    private void reloadCurrentPage() {
        handler.removeCallbacks(errorReloadTask);
        webView.reload();
    }

    private void goBack() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            Toast.makeText(this, R.string.nothing_to_go_back_to, Toast.LENGTH_SHORT).show();
        }
    }

    private void goForward() {
        if (webView.canGoForward()) {
            webView.goForward();
        } else {
            Toast.makeText(this, R.string.nothing_to_go_forward_to, Toast.LENGTH_SHORT).show();
        }
    }

    private void applyBrowserSettings() {
        WebSettings settings = webView.getSettings();
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(
                webView,
                KioskConfig.allowThirdPartyCookies(this));
        flushCookies();
    }

    private void applyRuntimeSettings() {
        if (KioskConfig.keepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void scheduleErrorReload() {
        handler.removeCallbacks(errorReloadTask);
        handler.postDelayed(
                errorReloadTask,
                KioskConfig.getErrorReloadDelaySeconds(this) * 1000L);
    }

    private void refreshAutoReloadTimer() {
        handler.removeCallbacks(autoReloadTask);
        int minutes = KioskConfig.getAutoReloadMinutes(this);
        if (minutes > 0) {
            handler.postDelayed(autoReloadTask, minutes * 60_000L);
        }
    }

    private void refreshCookieFlushTimer() {
        handler.removeCallbacks(cookieFlushTask);
        handler.postDelayed(cookieFlushTask, COOKIE_FLUSH_INTERVAL_MS);
    }

    private void flushCookies() {
        CookieManager.getInstance().flush();
    }

    private void flushCookiesIfReady() {
        if (webView != null) {
            flushCookies();
        }
    }

    private void registerNetworkCallback() {
        if (networkCallback != null) {
            return;
        }

        connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                if (networkWasLost && KioskConfig.reloadOnReconnect(MainActivity.this)) {
                    networkWasLost = false;
                    runOnUiThread(MainActivity.this::reloadCurrentPage);
                }
            }

            @Override
            public void onLost(Network network) {
                networkWasLost = true;
            }
        };
        connectivityManager.registerDefaultNetworkCallback(networkCallback);
    }

    private void unregisterNetworkCallback() {
        if (connectivityManager != null && networkCallback != null) {
            connectivityManager.unregisterNetworkCallback(networkCallback);
            networkCallback = null;
        }
    }

    private CheckBox addCheckBox(LinearLayout form, int label, boolean checked) {
        CheckBox checkBox = new CheckBox(this);
        checkBox.setText(label);
        checkBox.setChecked(checked);
        form.addView(checkBox, matchWidth());
        return checkBox;
    }

    private EditText createNumberInput(int hint, int value) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setText(Integer.toString(value));
        styleTextInput(input);
        return input;
    }

    private EditText createPinInput() {
        EditText input = new EditText(this);
        input.setHint(R.string.pin_hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
        input.setSingleLine(true);
        input.setGravity(Gravity.CENTER);
        input.setTextSize(28);
        input.setPadding(dp(14), dp(8), dp(14), dp(8));
        return input;
    }

    private TextView createPinDisplay() {
        TextView display = new TextView(this);
        display.setGravity(Gravity.CENTER);
        display.setTextColor(Color.rgb(18, 28, 42));
        display.setTextSize(28);
        display.setPadding(dp(14), dp(10), dp(14), dp(10));
        display.setBackgroundColor(Color.rgb(241, 245, 249));
        updatePinDisplay(display, new StringBuilder());
        return display;
    }

    private TextView createPinErrorText() {
        TextView error = new TextView(this);
        error.setText(" ");
        error.setTextColor(Color.rgb(180, 45, 45));
        error.setTextSize(14);
        error.setGravity(Gravity.CENTER);
        return error;
    }

    private LinearLayout createPinKeypad(
            StringBuilder pinValue, TextView pinDisplay, TextView errorText) {
        LinearLayout keypad = new LinearLayout(this);
        keypad.setOrientation(LinearLayout.VERTICAL);
        String[][] rows = {
                {"1", "2", "3"},
                {"4", "5", "6"},
                {"7", "8", "9"},
                {"", "0", "<"}
        };

        for (String[] rowValues : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            for (String value : rowValues) {
                Button button = createPinKeypadButton(value);
                if (!value.isEmpty()) {
                    button.setOnClickListener(view -> {
                        errorText.setText(" ");
                        if ("<".equals(value)) {
                            if (pinValue.length() > 0) {
                                pinValue.deleteCharAt(pinValue.length() - 1);
                            }
                        } else if (pinValue.length() < 4) {
                            pinValue.append(value);
                        }
                        updatePinDisplay(pinDisplay, pinValue);
                    });
                } else {
                    button.setEnabled(false);
                    button.setVisibility(View.INVISIBLE);
                }
                LinearLayout.LayoutParams params =
                        new LinearLayout.LayoutParams(0, dp(52), 1f);
                params.setMargins(dp(4), dp(4), dp(4), dp(4));
                row.addView(button, params);
            }
            keypad.addView(row, matchWidth());
        }
        return keypad;
    }

    private Button createPinKeypadButton(String value) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText("<".equals(value) ? "Back" : value);
        button.setTextSize(20);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private void updatePinDisplay(TextView display, StringBuilder pinValue) {
        StringBuilder displayValue = new StringBuilder();
        for (int index = 0; index < 4; index++) {
            displayValue.append(index < pinValue.length() ? "*" : "-");
            if (index < 3) {
                displayValue.append(" ");
            }
        }
        display.setText(displayValue.toString());
    }

    private Integer parseNumber(EditText input) {
        try {
            return Integer.parseInt(input.getText().toString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private boolean isValidNumber(Integer value) {
        return value != null && value >= 0 && value <= 1440;
    }

    private ViewGroup.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams spacedMatchWidth(int topMargin) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private LinearLayout createDialogPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(8), dp(24), dp(8));
        return panel;
    }

    private TextView createHelperText(int text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(83, 98, 95));
        label.setTextSize(16);
        return label;
    }

    private TextView createSectionLabel(int text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.rgb(20, 108, 91));
        label.setTextSize(14);
        label.setAllCaps(true);
        return label;
    }

    private void styleTextInput(EditText input) {
        input.setTextSize(17);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
    }

    private void showKeyboard(AlertDialog dialog, EditText input) {
        input.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        input.postDelayed(
                () -> {
                    InputMethodManager keyboard =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
                },
                150);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String normalizeUrl(String value) {
        String url = value.trim();
        if (!url.contains("://")) {
            return "https://" + url;
        }
        return url;
    }

    private boolean isAllowedUrl(String value) {
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme));
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private void enterLockTaskMode() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            return;
        }

        DevicePolicyManager policyManager =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (policyManager.isLockTaskPermitted(getPackageName())) {
            startLockTaskSafely();
        } else if (!screenPinningAttempted) {
            screenPinningAttempted = true;
            startLockTaskSafely();
        }
    }

    private void scheduleLockTaskRecheck() {
        handler.postDelayed(this::recheckLockTaskMode, 250L);
        handler.postDelayed(this::recheckLockTaskMode, 1_000L);
    }

    private void recheckLockTaskMode() {
        if (!KioskConfig.isKioskActive(this) || adminDialogVisible || temporarySystemNavigation) {
            return;
        }

        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
            screenPinningAttempted = false;
            enterLockTaskMode();
        }
    }

    private void startLockTaskSafely() {
        try {
            startLockTask();
        } catch (RuntimeException exception) {
            // Non-device-owner installs can only enter Android's user-confirmed screen pinning mode.
        }
    }

    private void applyKioskPolicyIfActive() {
        if (KioskConfig.isKioskActive(this)) {
            KioskPolicy.apply(this);
        }
    }

    private boolean isHomeLaunch(Intent intent) {
        return intent != null
                && (intent.hasCategory(Intent.CATEGORY_HOME)
                        || intent.getBooleanExtra(EXTRA_HOME_LAUNCH, false));
    }

    private void enterImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    private void configureEdgeToEdgeWindow() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }
    }

    private void configureSystemBarRehide() {
        getWindow().getDecorView().setOnSystemUiVisibilityChangeListener(
                visibility -> scheduleSystemBarRehide());
        getWindow().getDecorView().setOnApplyWindowInsetsListener(
                (view, insets) -> {
                    if (!adminDialogVisible) {
                        scheduleSystemBarRehide();
                    }
                    return insets;
                });
    }

    private void scheduleSystemBarRehide() {
        if (!KioskConfig.isKioskActive(this)) {
            handler.removeCallbacks(systemBarRehideTask);
            return;
        }
        handler.removeCallbacks(systemBarRehideTask);
        for (long delay : SYSTEM_BAR_REHIDE_DELAYS_MS) {
            handler.postDelayed(systemBarRehideTask, delay);
        }
    }

    private void onAdminDialogDismissed() {
        adminDialogVisible = false;
        scheduleSystemBarRehide();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void startWatchdog() {
        Intent intent = new Intent(this, WatchdogService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }
}
