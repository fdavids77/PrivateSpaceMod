# PrivateSpaceMod

[![Build](https://github.com/fdavids77/PrivateSpaceMod/actions/workflows/build.yml/badge.svg)](https://github.com/fdavids77/PrivateSpaceMod/actions/workflows/build.yml)

Unified Private Space module for rooted Pixel devices. Combines **PSLabelHider** + **PrivateSpaceUnlock** into a single LSPosed + Magisk module with new capabilities.

## Features

| Feature | Description |
|---|---|
| 🔓 **Double-tap unlock** | Double-tap home screen → unlocks Private Space instantly |
| 📱 **App picker overlay** | Floating pill with Private Space app icons — tap to launch |
| 👁️ **Label hider** | Hides "Private" label text from Pixel Launcher drawer |
| 🔒 **Icon hider** | Hides lock icon, settings button, and lock group |
| ⚡ **QS tile** | Backup unlock trigger from notification shade |

## How It Works

```
Double-tap home screen
        │
        ▼
 LSPosed hook intercepts gesture
        │
        ▼
 UserManager.requestQuietModeEnabled(false, user 10)
        │
        ▼
 Private Space unlocks
        │
        ▼
 Mini app-picker overlay appears
        │
        ▼
 Tap app icon → launches in Private Space (user 10)
```

## Requirements

- Rooted Pixel device (Magisk 26+)
- Android 15 or 16
- LSPosed installed (JingMatrix/Vector fork for Android 16)
- Pixel Launcher as default launcher

## Installation

### From GitHub Release

1. Download `PrivateSpaceMod-v2.0.zip` from [Releases](https://github.com/fdavids77/PrivateSpaceMod/releases)
2. Open **Magisk Manager** → Modules → Install from storage
3. Select `PrivateSpaceMod-v2.0.zip`
4. **Reboot**
5. Open **LSPosed Manager** → Modules → Enable **PrivateSpaceMod**
6. Set scope to **Pixel Launcher** (`com.google.android.apps.nexuslauncher`)
7. **Reboot again**
8. Double-tap home screen to test!

### From GitHub Actions (Latest Build)

1. Go to [Actions](https://github.com/fdavids77/PrivateSpaceMod/actions)
2. Click the latest successful **Build PrivateSpaceMod** run
3. Download **PrivateSpaceMod-Magisk** artifact
4. Flash the ZIP via Magisk Manager

### From Source (Termux)

```bash
git clone git@github.com:fdavids77/PrivateSpaceMod.git
cd PrivateSpaceMod
chmod +x deploy.sh
./deploy.sh --release v2.0
```

## Architecture

```
PrivateSpaceMod/
├── xposed-module/                      # LSPosed module (Android app)
│   ├── app/src/main/java/.../
│   │   ├── MainHook.java              # Entry: gesture hooks + label hider
│   │   ├── PrivateSpaceController.java # Unlock logic + app-picker overlay
│   │   └── PrivateSpaceTileService.java# QS tile (backup trigger)
│   ├── app/src/main/assets/
│   │   └── xposed_init                # LSPosed hook entry point
│   └── build.gradle.kts               # AGP 8.5.2, Java 17, Xposed API 82
│
├── magisk-module/                      # Magisk module (priv-app installer)
│   ├── META-INF/                       # Magisk installer scripts
│   ├── system/
│   │   ├── priv-app/PrivateSpaceMod/   # APK installed as privileged app
│   │   └── etc/permissions/            # Permission whitelist XML
│   ├── module.prop                     # Module identity
│   └── customize.sh                    # Installer with progress output
│
├── .github/workflows/
│   └── build.yml                       # CI: build APK → package ZIP → release
├── deploy.sh                           # One-command push from Termux
└── README.md
```

### Why Two Modules in One ZIP?

The **LSPosed module** (APK) hooks into Pixel Launcher for gesture interception and UI manipulation. But to get `MANAGE_USERS` and `INTERACT_ACROSS_USERS` permissions, the APK must be installed as a **privileged system app** — that's what the **Magisk module** does. The CI pipeline builds the APK, copies it into the Magisk module `system/priv-app/` structure, and packages everything into a single flashable ZIP.

## Replaces

This module supersedes both of these older projects:

- [PSLabelHider](https://github.com/fdavids77/PSLabelHider) — label/icon hiding (v1.15)
- [PrivateSpaceUnlock](https://github.com/fdavids77/PrivateSpaceUnlock) — QS tile unlock (v1.0)

**Uninstall both before installing PrivateSpaceMod:**

```bash
# Via Magisk Manager → Modules → Remove
# Or manually:
rm -rf /data/adb/modules/pslabelhider
rm -rf /data/adb/modules/psunlock
# Reboot, then flash PrivateSpaceMod
```

## Troubleshooting

### Double-tap doesn't trigger

- Verify LSPosed module is enabled with **Pixel Launcher** in scope
- Check logs: `adb logcat -s PSMod:*`
- Look for `"Using GestureDetector global hook fallback"` in logs — means the primary hook class didn't match your launcher version
- Force stop Pixel Launcher to reload hooks: `adb shell am force-stop com.google.android.apps.nexuslauncher`

### App picker shows no apps

- Private Space must have apps installed:
  ```bash
  su -c pm list packages --user 10 -3
  ```
- The picker filters out system packages — only third-party apps are shown

### Permissions not granted

```bash
# Verify priv-app is installed
adb shell su -c 'ls -la /system/priv-app/PrivateSpaceMod/'

# Check permissions are granted
adb shell su -c 'dumpsys package com.fdavids77.privatespacmod | grep "granted=true"'

# Expected output:
#   android.permission.MANAGE_USERS: granted=true
#   android.permission.INTERACT_ACROSS_USERS: granted=true
#   android.permission.INTERACT_ACROSS_USERS_FULL: granted=true
```

### "Private" label still showing

- Ensure LSPosed module scope includes `com.google.android.apps.nexuslauncher`
- Force stop Pixel Launcher:
  ```bash
  adb shell am force-stop com.google.android.apps.nexuslauncher
  ```
- Check LSPosed logs for hook confirmation: `adb logcat -s PSMod:*`

## Development

### Building locally

```bash
cd xposed-module
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/PrivateSpaceMod.apk
```

### Quick-testing changes (no reflash needed)

```bash
# Push updated APK directly over the installed one
adb push app/build/outputs/apk/release/PrivateSpaceMod.apk /data/local/tmp/
adb shell su -c 'cp /data/local/tmp/PrivateSpaceMod.apk \
  /data/adb/modules/privatespacmod/system/priv-app/PrivateSpaceMod/PrivateSpaceMod.apk'

# Restart Pixel Launcher to pick up changes
adb shell su -c 'am force-stop com.google.android.apps.nexuslauncher'

# For full hook reload: soft reboot (restarts zygote)
adb shell su -c 'setprop ctl.restart zygote'
```

### Creating a release

```bash
# From Termux or any git client
./deploy.sh --release v2.1
# GitHub Actions builds + creates a GitHub Release with the flashable ZIP
```

## Tested On

| Device | Android | Root | LSPosed | Status |
|---|---|---|---|---|
| Pixel 9 Pro XL (komodo) | 16 | Magisk 30.6 | JingMatrix v1.11.0 | ✅ Primary |
| Pixel 9 Pro XL (komodo) | 15 | Magisk 30.6 | JingMatrix v1.11.0 | ✅ |

## License

MIT
