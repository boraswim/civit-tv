# Debloat script for the Philips UHD Android TV (2021/22, TPV PH1M_EA_9970A, Android 11)
# Usage:   .\debloat-philips.ps1                 # remove everything in the lists below
#          .\debloat-philips.ps1 -Restore        # bring all of it back
#          .\debloat-philips.ps1 -Device <tv-ip>:5555
# Removal is per-user (pm uninstall --user 0); the system image is untouched,
# so -Restore or a factory reset always brings packages back.
# Kept on purpose: Netflix, Prime Video, YouTube, YouTube Music, Apple TV, Chromecast,
# Google Assistant, Ambilight, the TV tuner/channels, Philips settings and firmware updates.

param(
    [string]$Device = $env:TV_DEVICE,
    [switch]$Restore
)
if (-not $Device) { throw "Pass -Device <tv-ip>:5555, or set `$env:TV_DEVICE once for this PowerShell session" }

# Spyware / telemetry
$tracking = @(
    "org.droidtv.usagelogger",                 # Philips usage logging
    "com.google.android.feedback"              # Google feedback reporter
)

# Ads / sponsored content / silent installers
$ads = @(
    "com.google.android.tvrecommendations",    # Sponsored home-screen rows
    "org.droidtv.nettvrecommender",            # Philips promo recommendations
    "android.autoinstalls.config.tpv.device",  # Silently installs promoted apps
    "org.droidtv.demome"                       # Shop demo mode
)

# Plain bloat
$bloat = @(
    "com.google.android.play.games",
    "com.google.android.videos",               # Play Movies (discontinued)
    "com.android.printspooler",
    "com.android.dreams.basic",
    "org.droidtv.amazonalexa",                 # Alexa integration
    "io.mimi.sdk.service",                     # Mimi hearing profile
    "io.mimi.tvapp",
    "org.droidtv.calmancalibrator",            # Calman pro calibration
    "org.droidtv.astrahdplus",                 # Germany-only HD+ operator app
    "org.droidtv.m7opapp",                     # Benelux-only M7 operator app
    "org.droidtv.freeviewplayers",             # UK-only Freeview Play
    "uk.co.freeview.amc_catchup", "uk.co.freeview.amc_horror", "uk.co.freeview.bbc",
    "uk.co.freeview.bbc_sounds", "uk.co.freeview.ch4_vod", "uk.co.freeview.ch5",
    "uk.co.freeview.explore", "uk.co.freeview.fvpconfigauth", "uk.co.freeview.itv",
    "uk.co.freeview.mdsclient", "uk.co.freeview.onnow", "uk.co.freeview.sony_kids_vod",
    "uk.co.freeview.stv", "uk.co.freeview.tifbridge", "uk.co.freeview.uktv"
)

adb connect $Device | Out-Null
if ((adb -s $Device get-state 2>&1) -ne "device") { throw "TV not reachable/authorized at $Device" }

foreach ($p in $tracking + $ads + $bloat) {
    if ($Restore) { $r = adb -s $Device shell cmd package install-existing $p }
    else          { $r = adb -s $Device shell pm uninstall --user 0 $p }
    "{0,-45} {1}" -f $p, ($r -join " ")
}
