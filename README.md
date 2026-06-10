# CP Kiosk Prototype

This is a minimal Android kiosk shell for company-owned tablets. It does not use
Samsung Knox or a paid MDM. It provides:

- a dedicated launcher/home screen;
- lock-task kiosk mode when the app is provisioned as device owner;
- automatic launch after boot;
- a foreground watchdog service with a local heartbeat;
- a full-screen WebView for one configurable URL;
- a local admin panel protected by a left-edge swipe and four-digit PIN.

## Local admin panel

Swipe from the left edge of the screen toward the right, then enter the kiosk
PIN. The prototype default PIN is `4711`. The local menu allows staff to reload
the current page, return to the start page, navigate backward or forward, change
browser settings, or temporarily exit kiosk mode.

The default website for fresh deployments is `https://www.google.com`.

`Exit kiosk` returns to the Android home screen without changing the saved
Kiosk active setting. In Soft mode, selecting CP Kiosk as the default Home app
causes the Home button to return to the kiosk. Device-owner provisioning enables
the stronger dedicated-device policy.

The settings include the start URL, PIN, keep-screen-awake behavior,
third-party cookies, reload after page load errors, reload after network
reconnect, and an optional timed reload interval.

The app does not clear WebView cache, cookies, or DOM storage during normal
operation. Cookies are flushed to WebView storage after page loads and whenever
the app pauses. Persistent sign-in still depends on the website issuing a
long-lived cookie and keeping the server-side session valid.

Use HTTPS for production URLs. Cleartext HTTP is enabled in the prototype for
internal test environments only.

## Current scope

The prototype intentionally does not implement silent full-device remote desktop
control. Android does not grant a normal app unattended screen capture and global
touch injection. The next phase can add a small server-backed admin dashboard for
app recovery, configuration, logs, status, and updates.

## Open in Android Studio

1. Install Android Studio.
2. Open this repository as a project.
3. Allow Android Studio to install Android SDK 35 and its build tools.
4. Build the debug APK.

For a command-line build:

```powershell
.\gradlew.bat assembleDebug
```

If a corporate HTTPS-inspection gateway is in use, Java may also need the
company root CA added to a local truststore before Gradle can download build
dependencies. Do not disable TLS certificate validation.

Release builds are signed with the local deployment key configured in
`release-signing.properties`. Back up that file and `release-keystore.jks`
securely: future updates must be signed with the same key.

## Preview install

Install and open the app normally if you only want to preview the UI:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.example.platekiosk/.MainActivity
```

## Free office deployment

Android Enterprise setup-wizard QR enrollment blocks custom DPC apps unless
Google has approved and allowlisted the DPC. For a free private deployment,
prepare each new tablet in the office without adding Google or Samsung accounts,
enable USB debugging, connect it by USB, approve the office PC once, and run:

```powershell
.\deployment\deploy-kiosk-usb.ps1
```

The app then becomes the tablet home screen and enters lock-task mode.

## Remove device-owner mode

While USB debugging is still available:

```powershell
adb shell dpm remove-active-admin --user 0 com.example.platekiosk/.KioskDeviceAdminReceiver
adb uninstall com.example.platekiosk
```

If device-owner removal is unavailable on a particular Android build, factory
reset the development tablet.
