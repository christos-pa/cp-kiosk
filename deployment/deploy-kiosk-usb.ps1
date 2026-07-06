param(
    [string] $ApkPath = (Join-Path $PSScriptRoot "cp-kiosk-strong-v0.3.8.apk"),
    [bool] $RemoveMeetAccountBlocker = $true
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$adminComponent = "com.example.platekiosk/.KioskDeviceAdminReceiver"
$packageName = "com.example.platekiosk"
$meetPackage = "com.google.android.apps.tachyon"
$splashtopPackage = "com.splashtop.streamer.csrs"
$splashtopAccessibilityService =
    'com.splashtop.streamer.csrs/com.splashtop.streamer.addon.AccessibilityInputProvider\$CustomAccessibilityService'

if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB was not found at $adb"
}

if (-not (Test-Path -LiteralPath $ApkPath)) {
    throw "Deployment APK was not found at $ApkPath"
}

$connectedDevices = @(
    & $adb devices |
        Select-Object -Skip 1 |
        Where-Object { $_ -match "\tdevice$" }
)

if ($connectedDevices.Count -ne 1) {
    throw "Connect exactly one authorized Android tablet over USB, then run this script again."
}

function Test-PackageInstalled {
    param([string] $Package)
    $packageMatches = @(& $adb shell pm list packages $Package)
    return (@($packageMatches | Where-Object { $_.Trim() -eq "package:$Package" })).Count -gt 0
}

function Assert-NoAccounts {
    $accountText = & $adb shell dumpsys account
    if ($accountText -match "Accounts: 0") {
        return
    }

    if ($RemoveMeetAccountBlocker -and (Test-PackageInstalled $meetPackage)) {
        Write-Output "Removing Google Meet for this tablet user because its account blocks Device Owner..."
        & $adb shell pm uninstall --user 0 $meetPackage | Out-Null
        $accountText = & $adb shell dumpsys account
        if ($accountText -match "Accounts: 0") {
            return
        }
    }

    throw "Device Owner cannot be set because accounts already exist on the tablet. Factory reset, skip account sign-in, then run again."
}

function Grant-KioskPermissions {
    Write-Output "Granting CP Kiosk camera and microphone permissions..."
    & $adb shell pm grant $packageName android.permission.CAMERA | Out-Null
    & $adb shell pm grant $packageName android.permission.RECORD_AUDIO | Out-Null
}

function Configure-Splashtop {
    if (-not (Test-PackageInstalled $splashtopPackage)) {
        Write-Output "Splashtop is not installed. Install/sign in to Splashtop Streamer, then rerun this script to provision unattended access."
        return
    }

    Write-Output "Provisioning Splashtop unattended screen capture..."
    & $adb shell appops set $splashtopPackage PROJECT_MEDIA allow | Out-Null
    & $adb shell appops set $splashtopPackage BIND_ACCESSIBILITY_SERVICE allow | Out-Null
    & $adb shell pm grant $splashtopPackage android.permission.RECORD_AUDIO 2>$null | Out-Null

    Write-Output "Attempting to enable Splashtop Accessibility service..."
    & $adb shell settings put secure accessibility_enabled 1 | Out-Null
    & $adb shell settings put secure enabled_accessibility_services $splashtopAccessibilityService | Out-Null

    $enabledService = & $adb shell settings get secure enabled_accessibility_services
    if ($enabledService -notmatch [regex]::Escape("com.splashtop.streamer.csrs/")) {
        Write-Output "Splashtop Accessibility was not enabled silently. Enable it once manually in Android Accessibility settings if remote clicks do not work."
    }
}

$owners = & $adb shell dpm list-owners
$ownerText = $owners -join "`n"
if ($ownerText -match "no owners") {
    Assert-NoAccounts
} elseif ($ownerText -notmatch [regex]::Escape($packageName)) {
    throw "Another Device Owner is already set. This tablet must be factory reset before CP Kiosk strong mode can be installed."
}

Write-Output "Installing CP Kiosk..."
& $adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed."
}

if ($ownerText -match "no owners") {
    Write-Output "Assigning device-owner kiosk permissions..."
    & $adb shell dpm set-device-owner $adminComponent
    if ($LASTEXITCODE -ne 0) {
        throw "Device-owner setup failed. A factory reset may be required if Android setup has already been completed."
    }
} elseif ($ownerText -notmatch [regex]::Escape($packageName)) {
    throw "Another Device Owner is already set. This tablet must be factory reset before CP Kiosk strong mode can be installed."
} else {
    Write-Output "CP Kiosk is already Device Owner."
}

Grant-KioskPermissions
Configure-Splashtop

Write-Output "Launching CP Kiosk..."
& $adb shell am start -n "$packageName/.MainActivity" | Out-Null
Start-Sleep -Seconds 3

& $adb shell dumpsys activity activities |
    Select-String -Pattern "topResumedActivity|mLockTaskModeState|mLockTaskPackages" -Context 0,2

if (Test-PackageInstalled $splashtopPackage) {
    & $adb shell appops get $splashtopPackage PROJECT_MEDIA BIND_ACCESSIBILITY_SERVICE
}

Write-Output "Deployment complete."
