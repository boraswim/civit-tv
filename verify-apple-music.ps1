# Checks that a downloaded Apple Music APK or split bundle (.apkm/.apks/.xapk) is genuine,
# unmodified, and complete enough to run on the Mi Box S (Android 9, 32-bit ARM).
# Usage:   .\verify-apple-music.ps1 .\AppleMusic.apkm
#          .\verify-apple-music.ps1 .\AppleMusic.apkm -Install     # install on the TV if it passes
# Reference certificates come from the Play Store copy on the owner's phone (v6.5.3):
#   Apple's original key (used for Android 7-12):  CN=Apple Inc, Cupertino
#   Google Play rotated key (used for Android 13+): CN=Android, Google Inc.
# A file that was modified in any way can't carry either signature.

param(
    [Parameter(Mandatory)][string]$Path,
    [switch]$Install,
    [string]$Device = $env:TV_DEVICE
)
if (-not $Device) { throw "Pass -Device <tv-ip>:5555, or set `$env:TV_DEVICE once for this PowerShell session" }

$appleKeys = @{
    "88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa" = "Apple Inc (original key)"
    "771d8674d3d9837c9edf11b11873443998f19105abcecab425ed9b8e6fefff9b" = "Google Play signing key (rotated)"
}
$tvSdk = 28                                   # Android 9
$tvAbiSplits = @("config.armeabi_v7a", "config.armeabi")
$tvAbis = @("armeabi-v7a", "armeabi")

$bt = "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0"
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot" }
$Path = (Resolve-Path $Path).Path
$ok = $true

# Bundles are zips of split APKs; unpack each one into its own fresh folder.
if ($Path -match "\.(apkm|apks|xapk|zip)$") {
    $out = Join-Path $env:TEMP ("apple-music-bundle-" + [guid]::NewGuid())
    New-Item -ItemType Directory $out | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    try { [System.IO.Compression.ZipFile]::ExtractToDirectory($Path, $out) }
    catch { "FAIL  can't open the bundle (it may be an old encrypted .apkm): $($_.Exception.Message)"; return }
    $apks = Get-ChildItem $out -Recurse -Filter *.apk | Select-Object -ExpandProperty FullName
} else {
    $apks = @($Path)
}
if (-not $apks) { "FAIL  no APK files found"; return }

$base = $null
$splits = @()
$installSet = @()
foreach ($apk in $apks) {
    $name = Split-Path $apk -Leaf
    $badging = & "$bt\aapt2.exe" dump badging $apk 2>&1
    $split = $badging | Select-String "^package: .*? split='([^']+)'" | ForEach-Object { $_.Matches[0].Groups[1].Value }

    # Signature: must verify, and every signer must be one of Apple's keys.
    $sig = cmd /c "`"$bt\apksigner.bat`" verify --print-certs `"$apk`" 2>&1"
    $digests = $sig | Select-String "certificate SHA-256 digest: (\w+)" |
        Where-Object { $_.Line -notmatch "Source Stamp" } |
        ForEach-Object { $_.Matches[0].Groups[1].Value } | Select-Object -Unique
    $unknown = $digests | Where-Object { -not $appleKeys.ContainsKey($_) }
    if ($LASTEXITCODE -ne 0 -or -not $digests -or $unknown) {
        "FAIL  $name - signature invalid or not Apple's"; $ok = $false; continue
    }
    "OK    $name signed by " + (($digests | ForEach-Object { $appleKeys[$_] }) -join " + ")

    if (-not $split) { $base = @{ Path = $apk; Badging = $badging } }
    else { $splits += $split }

    # Only send the TV the pieces it can use: base, its CPU, and density/language splits.
    $isOtherAbi = $split -match "^config\.(arm64_v8a|x86|x86_64|mips)"
    if (-not $isOtherAbi) { $installSet += $apk }
}

if (-not $base) { "FAIL  no base APK in the download"; $ok = $false }
else {
    $pkg = ($base.Badging | Select-String "^package: name='([^']+)'.*? versionName='([^']*)'").Matches
    if ($pkg -and $pkg[0].Groups[1].Value -eq "com.apple.android.music") { "OK    package com.apple.android.music, version $($pkg[0].Groups[2].Value)" }
    else { "FAIL  not the Apple Music package"; $ok = $false }

    $minSdk = [int](($base.Badging | Select-String "^(min)?[sS]dkVersion:'(\d+)'").Matches[0].Groups[2].Value)
    if ($minSdk -le $tvSdk) { "OK    needs Android SDK $minSdk (TV has $tvSdk)" }
    else { "FAIL  needs Android SDK $minSdk, but the TV has $tvSdk (Android 9)"; $ok = $false }

    # 32-bit code lives either in the base APK or in a config.armeabi_v7a split.
    $baseAbis = $base.Badging | Select-String "^native-code: (.*)" |
        ForEach-Object { $_.Matches[0].Groups[1].Value -replace "'", "" -split " " }
    if ($baseAbis | Where-Object { $_ -in $tvAbis }) { "OK    32-bit code included in the base APK" }
    elseif ($splits | Where-Object { $_ -in $tvAbiSplits }) { "OK    32-bit code included (split $(($splits | Where-Object { $_ -in $tvAbiSplits }) -join ', '))" }
    elseif ($apks.Count -eq 1) { "FAIL  this is only the base APK; the 32-bit code is missing. Download the BUNDLE (.apkm) instead"; $ok = $false }
    else { "FAIL  bundle has no 32-bit (armeabi-v7a) split; splits: $($splits -join ', ')"; $ok = $false }
}

if (-not $ok) { "`nREJECT  don't install this file"; return }
"`nPASS  genuine, complete Apple Music for the TV ($($installSet.Count) file(s))"

if ($Install) {
    adb connect $Device | Out-Null
    if ($installSet.Count -eq 1) { adb -s $Device install -r $installSet[0] }
    else { adb -s $Device install-multiple -r @installSet }
}
