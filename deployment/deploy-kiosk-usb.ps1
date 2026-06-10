param(
    [string] $ApkPath = (Join-Path $PSScriptRoot "cp-kiosk-strong-v0.3.apk")
)

$ErrorActionPreference = "Stop"
$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$adminComponent = "com.example.platekiosk/.KioskDeviceAdminReceiver"
$packageName = "com.example.platekiosk"

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

Write-Output "Installing CP Kiosk..."
& $adb install -r $ApkPath
if ($LASTEXITCODE -ne 0) {
    throw "APK installation failed."
}

Write-Output "Assigning device-owner kiosk permissions..."
& $adb shell dpm set-device-owner $adminComponent
if ($LASTEXITCODE -ne 0) {
    throw "Device-owner setup failed. Remove any Google or Samsung accounts and try again. A factory reset may be required if Android setup has already been completed."
}

Write-Output "Launching CP Kiosk..."
& $adb shell monkey -p $packageName -c android.intent.category.LAUNCHER 1 | Out-Null
Start-Sleep -Seconds 3

& $adb shell dumpsys activity activities |
    Select-String -Pattern "topResumedActivity|mLockTaskModeState|mLockTaskPackages" -Context 0,2

Write-Output "Deployment complete."
