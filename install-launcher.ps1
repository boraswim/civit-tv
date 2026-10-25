# Installs the Apple TV-style launcher and makes it the home screen.
# Usage:   .\install-launcher.ps1            # build (if needed), install, set as home
#          .\install-launcher.ps1 -Revert    # go back to the stock Google TV launcher
# Android TV ignores set-home-activity while the stock launcher (a system app with higher
# priority) is enabled, so it is disabled here. It stays on the device; -Revert re-enables it.

param(
    [string]$Device = $env:TV_DEVICE,
    [switch]$Revert
)
if (-not $Device) { throw "Pass -Device <tv-ip>:5555, or set `$env:TV_DEVICE once for this PowerShell session" }

$stock = "com.google.android.tvlauncher"
$apk = Join-Path $PSScriptRoot "launcher\app\build\outputs\apk\release\app-release.apk"

adb connect $Device | Out-Null
if ((adb -s $Device get-state 2>&1) -ne "device") { throw "TV not reachable/authorized at $Device" }

if ($Revert) {
    adb -s $Device shell pm enable $stock
    adb -s $Device shell cmd package set-home-activity "$stock/.MainActivity"
    adb -s $Device shell input keyevent KEYCODE_HOME
    return
}

if (-not (Test-Path $apk)) {
    Push-Location (Join-Path $PSScriptRoot "launcher")
    & .\gradlew.bat assembleRelease --console=plain -q
    Pop-Location
}
adb -s $Device install -r $apk
adb -s $Device shell pm disable-user --user 0 $stock
adb -s $Device shell cmd package set-home-activity com.tvenhance.home/.MainActivity

# Remote "mouse mode" for sideloaded phone apps; accessibility services can only be
# switched on from Settings or adb, so add ours to whatever is already enabled.
$mouse = "com.tvenhance.home/com.tvenhance.home.MouseService"
$enabled = (adb -s $Device shell settings get secure enabled_accessibility_services).Trim()
if ($enabled -notmatch [regex]::Escape($mouse)) {
    $list = if ($enabled -and $enabled -ne "null") { "$enabled`:$mouse" } else { $mouse }
    adb -s $Device shell settings put secure enabled_accessibility_services "$list"
}
adb -s $Device shell settings put secure accessibility_enabled 1
# Lets ÇivitTV switch mouse mode back on by itself if Android ever drops it.
adb -s $Device shell pm grant com.tvenhance.home android.permission.WRITE_SECURE_SETTINGS
# Media-session access for the home screen's "now playing" card.
adb -s $Device shell cmd notification allow_listener com.tvenhance.home/com.tvenhance.home.NowPlayingListener
adb -s $Device shell input keyevent KEYCODE_HOME
