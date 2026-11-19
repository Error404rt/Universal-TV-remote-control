# Universal Remote Control

A local-first Android TV remote with a pure-black interface and custom white outline controls.
Built for the Xiaomi Mi TV 4A 32 (`MiTV-MSSP0` / `amelie`) and other Android TV / Google TV devices.

## Interface

The app has three horizontally swiped screens:

1. **Touchpad** (swipe right from Remote): rounded square outline surface. Bluetooth HID gives a real relative pointer; ADB gives tap/swipe; Android TV Remote translates taps and swipes into D-pad focus movement.
2. **Remote** (start screen): circular outline D-pad with OK, plus separate outline Menu, Back and Home buttons. Menu opens the TV's own menu. Up to three quick-launch app slots and a plus action live below the buttons.
3. **Devices**: saved and discovered TVs, top-right **Connect**, manual IP, refresh and connection status.

The bottom bar uses a small dot for the active screen and outline icons for the inactive ones.
The left indicator reflects the selected connection method: Bluetooth, phone or Wi-Fi.

Everything is drawn with Compose Canvas / Foundation: no Material components, filled buttons, shadows or cards.

## First launch

A one-time onboarding sheet explains:

- phone and TV must share a network;
- Android TV Remote pairing uses the six-character code shown by the TV;
- **USB debugging is not required for the remote** — it is only needed for the optional ADB features (full installed-app list, package launch and touch coordinates);
- on Xiaomi: Settings → About → click **Build** seven times → enable **USB debugging** / **Network debugging** if ADB is wanted;
- Bluetooth HID pairing starts from the TV's Bluetooth menu.

## Connection methods

The Connect action contains exactly three choices.

### 1. Bluetooth HID

The phone registers as a Bluetooth HID keyboard, relative mouse and consumer-control device.
Pairing is initiated on the TV: **Bluetooth → Add accessory → Universal Remote Control**.
The phone cannot force a TV to become a HID host; firmware support varies.

### 2. Phone hotspot

The TV must already have this hotspot saved (or be connected to it once from the TV's network settings).
Android does not let an ordinary app enable a hotspot silently, so the app opens the system tethering screen, reports the hotspot interface/address, and then discovers the TV over the hotspot subnet.
If the TV has not saved the hotspot, no phone-side app can make it join automatically.

### 3. Shared Wi-Fi

Phone and TV are connected to the same router/network.
The app discovers the TV through DNS-SD and a bounded private IPv4 subnet probe.

## Implemented providers

- **Android TV Remote v2** (primary): discovers `_androidtvremote2._tcp`, pairs using the TV's six-character code over TLS 6467, pins the TV certificate, and controls it over TLS 6466. No USB debugging is needed.
- **ADB TCP** (optional): classic network ADB on port 5555 with the TV-side authorization prompt. Adds installed-app inventory, package launch and tap/swipe input. Android 11+ wireless-debugging pairing is not implemented.
- **Bluetooth HID**: true relative pointer path when the TV supports Bluetooth mouse input.

Android TV Remote and ADB are network protocols selected automatically after choosing **Phone hotspot** or **Shared Wi-Fi**.
The app prefers Android TV Remote when both protocols are available because it requires no ADB authorization.
mDNS-advertised endpoints are verified with a TCP probe, so a stale service entry is not shown as reachable.
If the TV refuses the Android TV Remote pairing port (6467) but exposes ADB, the app automatically retries the same TV over ADB.
This is the expected path for the tested Mi TV 4A 32 unit, whose remote service ports were closed while ADB 5555 was open.

## Multi-TV support

TVs that have been connected are persisted with their protocol and connection method.
Saved TVs remain visible while offline and can be reconnected or removed independently.
Selecting another TV replaces the active connection; quick-app selections are stored per TV.

## Quick apps

The plus button opens the app picker and allows up to three shortcuts.
With ADB, the list contains launchable apps installed on the TV.
With Android TV Remote only, the TV does not expose its installed-app list, so the picker shows a curated catalog (YouTube, Netflix, Prime Video, Disney+, IVI, Kodi and others) and launch depends on the TV's app-link capability.

## Xiaomi Mi TV 4A 32

- Model: `MiTV-MSSP0`, codename `amelie`, Android 9 / API 28, `armeabi-v7a`.
- Network behavior observed on the test unit: TCP 6466 and 6467 (Android TV Remote control/pairing) refused connections, while TCP 5555 (ADB) was open.
- The app therefore falls back to ADB automatically when the remote pairing port is closed. The TV shows its normal «Разрешена отладка?» prompt on the first ADB connection.
- Android TV Remote remains the preferred path on firmware where ports 6466/6467 are actually listening.
- ADB is also required for the installed-app list, package launch and tap/swipe on this unit. Bluetooth HID remains an option when the TV accepts keyboard/mouse pairing.
- Remote/ADB command behavior still needs the final on-device check; the model is not marked fully Verified.

## Limitations

- A normal Android app cannot enable/disable the phone hotspot or force a TV to join it.
- Android TV Remote exposes key events and app links, not a system pointer or the installed-app inventory.
- ADB tap/swipe is touch injection, not a system mouse cursor.
- Bluetooth HID depends on TV-side HID host support.
- Samsung Tizen, LG webOS, Roku and VIDAA need separate protocols and are not implemented.

## Build

- JDK 17
- Android SDK platform 36, build-tools 36.0.0
- Gradle 9.6.0

```bash
gradle assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions uploads the debug APK artifact as `universal-remote-debug`.

## Tests

```bash
gradle testDebugUnitTest
```

Unit tests cover the protobuf wire codec, Android TV pairing digest, private-network validation and TV candidate merging.

## Attribution

Android TV Remote v2 interoperability is adapted from the MIT-licensed OpenTV-Remote project.
ADB transport uses Kadb 1.3.0 (Apache-2.0).
See `docs/THIRD_PARTY_NOTICES.md`.
