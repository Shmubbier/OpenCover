# Setting up OpenCover

For the Galaxy Z Flip 5 on One UI 8 (Android 16). Most of it is done once; step 6 is repeated after every reboot, and only if you use Shizuku.

## What you need
- The OpenCover APK (`OpenCover-<version>.apk` from the GitHub releases).
- A computer with [adb](https://developer.android.com/tools/releases/platform-tools) for one command in step 4. Without a computer, skip step 4: everything else works, but apps outside Samsung's cover list say "Open phone to continue".
- Optional: [Good Lock](https://galaxystore.samsung.com/detail/com.samsung.android.goodlock) with MultiStar, and [Shizuku](https://github.com/RikkaApps/Shizuku/releases).

## 1. Install
Open the APK on the phone, or from the computer:
```
adb install OpenCover-0.1.apk
```
A release APK and a self-built debug APK are signed with different keys. To switch from one to the other, uninstall first; this resets OpenCover's settings.

## 2. Allow restricted settings
OpenCover is sideloaded, so Android hides its Accessibility and Notification access switches until you allow them:
Settings > Apps > OpenCover > ⋮ (top right) > **Allow restricted settings**.

## 3. Run the setup checklist
Open OpenCover on the cover screen. The checklist turns each item green once it's done; tap an item to open its settings page.

| Item | Needed for |
|---|---|
| Accessibility (required) | Returning to OpenCover on Home, opening apps on the cover, the navigation bar |
| Display over other apps (required) | Keeping the cover awake, pop-ups |
| Ignore battery optimization (recommended) | Stops Android from putting OpenCover to sleep |
| Notification access (recommended) | Notifications, pop-ups, media player, edge lighting |
| OpenCover AOD and lock screen (recommended) | OpenCover's clock while the phone sleeps, and its lock screen. One tap turns both on; then the checklist asks for Samsung's AOD to be set to Always (step 5) |
| Banking apps (recommended) | Apps that refuse to run while an accessibility service is on (GCash, ticked already when installed). OpenCover turns its service off while they're open and back on after; needs step 4 and Usage access |
| Secure settings (recommended) | Step 4 |
| Usage access, Phone calls, Microphone, Notifications (optional) | Drawer sorting, cover call screen, voice replies, the running notification |

OpenCover's accessibility service never reads screen content. See [Why Accessibility?](../README.md#why-accessibility).

## 4. Let every app open on the cover (computer, once)
```
adb shell pm grant dev.opencover android.permission.WRITE_SECURE_SETTINGS
```
Samsung only opens apps that are on Good Lock MultiStar's cover list on the cover. With this permission OpenCover adds every installed app to that list, and each new app as you install it, so no app needs Shizuku to open on the cover. It also lets OpenCover turn itself back on after a banking app (Settings > Banking apps).

The list belongs to MultiStar: open Good Lock > MultiStar > Galaxy Foldable > Launcher Widget once so it exists. If it doesn't, OpenCover leaves the setting alone.

## 5. Samsung settings
- Settings > Notifications > Cover screen: turn off Samsung's cover pop-ups, so you don't get each one twice.
- For OpenCover's AOD: Settings > Lock screen > Always On Display set to **Always**, with **Tap to show** off. OpenCover draws its clock over Samsung's, so Samsung's must stay on. Change OpenCover's AOD and lock screen later in OpenCover's Settings > Lock screen & AOD.

## 6. Shizuku (optional, after every reboot)
Shizuku adds the extras in the Shizuku group of OpenCover's Settings > Phone & display: keep the cover on when opened, deep sleep, 5G, cover density and per-app density, per-app rotation, multi-finger mirror touch, and the Wi-Fi, Bluetooth, data and similar tiles.

Android ends Shizuku on every reboot, and without root it can't start itself. Start it again in one of two ways:
- On the phone: open Shizuku on the cover (it's on MultiStar's cover list) and tap **Start via Wireless debugging**. Needs Wi-Fi, and Shizuku paired once with Wireless debugging.
- From a computer:
  ```
  adb shell 'p=$(pm path moe.shizuku.privileged.api | cut -d: -f2); $(dirname $p)/lib/arm64/libshizuku.so'
  ```

The first time, allow OpenCover in Shizuku. Its permission dialog opens on the inner screen, so allow it from the cover instead: open Shizuku on the cover > Authorized applications > turn on OpenCover.

## Troubleshooting
- **"Open phone to continue"** for an app: do step 4, then open OpenCover once (it adds apps when it starts), or start Shizuku.
- **Swiping Home shows Samsung's cover home**: Accessibility was turned off. Turn it back on in step 3. Force-stopping OpenCover also turns it off.
- **A Shizuku feature stopped working after a restart**: start Shizuku again (step 6).
- **A banking app complains about accessibility or developer options**: add it under Settings > Banking apps.
