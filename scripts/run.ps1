# Build, install, open on the cover screen, and save a cover screenshot to build/cover.png.
param([switch]$NoBuild)
# Not 'Stop': PowerShell 5 turns adb's progress output on stderr into terminating errors.
$ErrorActionPreference = 'Continue'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SERIAL = 'R5CW71QQAGW'
$root = Split-Path $PSScriptRoot
if (-not $NoBuild) {
    & "$root\gradlew.bat" -p $root :app:assembleDebug
    if ($LASTEXITCODE) { exit 1 }
}
adb install -r "$root\app\build\outputs\apk\debug\app-debug.apk"
if ($LASTEXITCODE) { exit 1 }
adb shell input keyevent KEYCODE_WAKEUP
adb shell am start --display 1 -n dev.opencover/.ui.CoverActivity
Start-Sleep 2
New-Item -ItemType Directory -Force "$root\build" | Out-Null
adb shell screencap -d 4630947181303254916 -p /sdcard/oc.png
adb pull /sdcard/oc.png "$root\build\cover.png" 2>&1 | Out-Null
"screenshot: $root\build\cover.png"
