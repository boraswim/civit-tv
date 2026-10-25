# Debloat script for the Huawei Mate 20 Pro (LYA-L29, EMUI 10.1, Android 10)
# Usage:   .\debloat-huawei.ps1                 # remove everything in the lists below
#          .\debloat-huawei.ps1 -Restore        # bring all of it back
#          .\debloat-huawei.ps1 -Device <serial> # pick a phone when several are plugged in
# Removal is per-user (pm uninstall --user 0); the system image is untouched,
# so -Restore or a factory reset always brings packages back.
# Deliberately conservative: nothing that calls, messages, notes, email, camera, gallery,
# Huawei ID / HMS, Health, Wallet, backup, updates or any app you installed depend on.

param(
    [string]$Device = "",   # USB serial; empty = the first phone connected over USB
    [switch]$Restore
)

if (-not $Device) {
    $Device = (adb devices | Select-String "^(\S+)\s+device$" | Where-Object { $_ -notmatch ":\d+\s" } | Select-Object -First 1).Matches.Groups[1].Value
}

# Spyware / telemetry
$tracking = @(
    "com.huawei.android.chr",                  # Huawei connectivity history reporter
    "com.huawei.hiviewtunnel",                 # Uploads diagnostic logs to Huawei
    "com.google.android.feedback",             # Google feedback reporter
    "com.facebook.appmanager",                 # Preinstalled Facebook background services
    "com.facebook.services",                   # (the Facebook/Messenger/Instagram apps keep working)
    "com.facebook.system"
)

# Ads / promotions / silent installers
$ads = @(
    "com.huawei.magazine",                     # Magazine Unlock: lock-screen promos
    "com.huawei.recsys",                       # App/content recommendations
    "com.huawei.hifolder",                     # "Recommended apps" inside home-screen folders
    "com.huawei.fastapp",                      # Quick Apps (pushes promoted mini-apps)
    "com.huawei.autoinstallapkfrommcc",        # Installs region/carrier promo apps
    "com.ebay.carrier",                        # Preinstalled eBay stub
    "com.huawei.tips",                         # Tips app
    "com.huawei.tipsove"
)

# Plain bloat
$bloat = @(
    "com.huawei.android.karaoke",
    "com.huawei.himovie.overseas",             # Huawei Video
    "com.android.mediacenter",                 # Huawei Music
    "com.google.android.music",                # Play Music (discontinued)
    "com.google.android.videos",               # Play Movies (discontinued)
    "com.android.dreams.basic",                # Screensavers
    "com.android.dreams.phototable"
)

if ((adb -s $Device get-state 2>&1) -ne "device") { throw "Phone not connected/authorized ($Device)" }

foreach ($p in $tracking + $ads + $bloat) {
    if ($Restore) { $r = adb -s $Device shell cmd package install-existing $p }
    else          { $r = adb -s $Device shell pm uninstall --user 0 $p }
    "{0,-45} {1}" -f $p, ($r -join " ")
}
