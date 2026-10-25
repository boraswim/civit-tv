# Debloat script for Xiaomi Mi Box S (MIBOX4, Android TV 9)
# Usage:   .\debloat.ps1                 # remove everything in the lists below
#          .\debloat.ps1 -Restore        # bring all of it back
#          .\debloat.ps1 -Device <tv-ip>:5555
# Removal is per-user (pm uninstall --user 0); the system image is untouched,
# so -Restore or a factory reset always brings packages back.

param(
    [string]$Device = $env:TV_DEVICE,
    [switch]$Restore
)
if (-not $Device) { throw "Pass -Device <tv-ip>:5555, or set `$env:TV_DEVICE once for this PowerShell session" }

# Spyware / telemetry
$tracking = @(
    "tv.alphonso.alphonso_eula",               # Alphonso ACR: fingerprints on-screen content for ad targeting
    "com.miui.tv.analytics",                   # Xiaomi analytics
    "com.google.android.feedback",             # Google feedback reporter
    "com.google.android.tv.bugreportsender"    # Bug report uploader
)

# Ads / sponsored content / silent installers
$ads = @(
    "com.google.android.tvrecommendations",    # Sponsored home-screen rows
    "com.mitv.tvhome.atv",                     # PatchWall promo feed
    "com.mitv.tvhome.michannel",               # "Mi Channel" promo row
    "com.xm.webcontent",                       # Xiaomi web promo content
    "android.autoinstalls.config.xioami.mibox3" # Silently installs promoted apps
)

# Plain bloat
$bloat = @(
    "com.google.android.play.games",
    "com.google.android.videos",               # Play Movies (discontinued)
    "com.mitv.videoplayer",
    "com.mitv.milinkservice",                  # Mi casting (Chromecast still works)
    "com.mitv.download.service",
    "com.android.camera2",
    "com.android.printspooler",
    "com.android.dreams.basic"
)

# Disabled rather than removed so they are easy to re-enable
$disable = @(
    "com.xiaomi.mitv.updateservice"            # Firmware updater (would reinstall bloat)
)

adb connect $Device | Out-Null
if ((adb -s $Device get-state 2>&1) -ne "device") { throw "TV not reachable/authorized at $Device" }

$remove = $tracking + $ads + $bloat
foreach ($p in $remove) {
    if ($Restore) { $r = adb -s $Device shell cmd package install-existing $p }
    else          { $r = adb -s $Device shell pm uninstall --user 0 $p }
    "{0,-45} {1}" -f $p, ($r -join " ")
}
foreach ($p in $disable) {
    if ($Restore) { $r = adb -s $Device shell pm enable $p }
    else          { $r = adb -s $Device shell pm disable-user --user 0 $p }
    "{0,-45} {1}" -f $p, ($r -join " ")
}
