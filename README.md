# Universal Remote Control

A local-first Android TV remote with a pure-black interface and custom white outline controls.

## Interface

- Three horizontal swipe screens: Devices, Remote and Touchpad.
- Remote: circular D-pad with OK, separate outline Menu, Back and Home controls.
- Bottom indicators: the active screen is a small white dot; inactive screens show outline icons. The left icon reflects the active connection type (Wi-Fi, phone hotspot or Bluetooth).
- Quick-launch app slots and a plus action to choose apps installed on the TV.
- Touchpad: rounded rectangular surface with click, right-click and Back controls.

## Connection methods

1. **Shared Wi-Fi**: phone and TV are on the same local network.
2. **Phone hotspot**: the TV must already have the phone hotspot credentials saved or be connected to it once from TV network settings. An ordinary Android app cannot force an arbitrary TV to join a hotspot or read the hotspot password.
3. **Bluetooth HID**: the phone presents itself as a Bluetooth keyboard/mouse. The TV must support Bluetooth HID host input and initiate pairing from its Bluetooth accessories screen.

## Implemented local providers

- **Android TV Remote v2**: discovers `_androidtvremote2._tcp`, pairs with the six-character code shown on the TV over TLS port 6467, then controls the TV over TLS port 6466. USB debugging is not required.
- **Xiaomi MiLink**: discovers Xiaomi Mi TV / Mi Box devices advertising a Xiaomi model TXT record under _googlecast._tcp and connects to MiLink on TCP 6091. No ADB or USB debugging is required for this provider.
- **ADB TCP / Android Wireless debugging**: optional advanced provider for Android-based TVs that expose ADB and accept the TV-side authorization prompt or pairing code.
- **Bluetooth HID**: phone-as-keyboard/mouse provider for televisions that support HID host input.

Android TV Remote v2 currently sends remote key events, but does not expose installed-app inventory or a standard system mouse pointer. The installed Leanback app list and app launching currently require ADB. MiLink supports key navigation only. Bluetooth HID is the true pointer path.

MiLink sends real Android key events including D-pad, OK, Back, Home and Menu. It does not expose a standard system mouse pointer or installed-app list; touchpad movement in a MiLink session is translated into D-pad navigation. Bluetooth HID is the current true mouse-pointer path. ADB tap/swipe is a touch-input fallback.

## Priority test TV

- Xiaomi MiTV-MSSP0 / Mi TV 4A 32
- Device codename: amelie
- Android 9 / API 28
- Board: m5621
- ABI: armeabi-v7a

MiLink discovery and the port-6091 provider are included for this model family, but the exact firmware still needs physical verification. If the TV does not advertise the Xiaomi service or TCP 6091 is closed, use ADB only if its firmware exposes network ADB, or try Bluetooth HID if the TV supports keyboard/mouse pairing.

## Compatibility policy

A brand in the catalogue is a candidate, not a guarantee. Android TV / Google TV sets can expose Android TV Remote v2, ADB or another service depending on model and firmware. Samsung Tizen, LG webOS, Roku and VIDAA require their own providers and are not implemented in this build. See docs/compatibility-matrix.md.

## Build

GitHub Actions creates a debug APK artifact named universal-remote-debug after a successful build.

- JDK 17
- Android SDK platform 36
- Gradle 9.6.0
