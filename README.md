# REXT Remote

A local Android TV remote with a minimal black-and-white outline interface.

> Project status: active prototype. The custom UI, ADB discovery foundation, encrypted ADB identity storage, ADB input client, Bluetooth HID input provider and initial device catalog are in progress. Physical TV compatibility is not yet verified.

## Visual direction

The app uses the supplied reference as its design specification:
- Pure black background.
- Thin white rounded outline controls and icons.
- No Material Design components, Material icons, filled buttons, shadows or decorative cards.
- Circular D-pad with four outline arrows and OK.
- Menu, Back and Home as separate outline controls.
- Square outline touch surface for mouse mode.
- Three small section indicators. They are visual indicators, not tappable tabs.
- Horizontal swipe navigation: Mouse ↔ Remote ↔ Devices.

Compose Foundation and Canvas are used as a rendering/input toolkit only. The interface itself is custom-drawn.

## Three connection choices

The selected TV's Connect action offers exactly three connection methods:

1. **Bluetooth** — phone advertises as a Bluetooth HID keyboard/mouse. The TV must support Bluetooth HID host input and pair from its Bluetooth accessory menu.
2. **Phone hotspot** — the TV joins the phone's normal internet-sharing hotspot. REXT then discovers it on that local network and connects using the TV's supported protocol.
3. **Shared Wi-Fi** — phone and TV are already on the same local network.

These are connection methods, not app navigation sections. The connected TV shows the icon matching the selected method: Bluetooth, phone, or Wi-Fi.

A phone hotspot is network topology, not a remote-control protocol. The app cannot universally force a TV to join the hotspot or enter its Wi-Fi password. The TV needs to have the hotspot credentials saved or the user must connect it once in TV settings. Once the TV is attached and reachable, REXT can discover and connect to it.

## Current connection foundation

- ADB mDNS discovery for classic ADB, Android 11+ Wireless debugging and pairing endpoints.
- Bounded local IPv4 subnet probe for classic ADB TCP port 5555 when mDNS is absent.
- ADB identity encrypted with AES-GCM using Android Keystore.
- ADB model/firmware inspection, key events, touch gestures and Leanback-launchable app discovery.
- Bluetooth HID keyboard, relative mouse and consumer-control reports.
- Manual IP fallback for classic ADB TCP.
- The app only displays a TV as connected after an ADB shell handshake or Bluetooth HID connection callback succeeds.

### Important first-time steps

- Classic ADB over TCP requires the TV to expose its ADB network endpoint and accept the app's RSA authorization prompt.
- Android 11+ Wireless debugging requires the user to enable Wireless debugging on the TV and complete its pairing-code flow.
- USB debugging being enabled alone does not always expose port 5555 over Wi-Fi.
- Bluetooth HID pairing is initiated from the TV's Bluetooth accessory menu. Keep REXT open in the foreground while using the HID mouse/keyboard mode; Android may unregister HID apps when the app leaves the foreground.

## TV compatibility

See the compatibility catalog in docs/compatibility-matrix.md.

The catalog includes Android TV / Google TV, Samsung Tizen, LG webOS, Roku, Fire TV, VIDAA, SmartCast and other candidate platform families. A brand or model family is a candidate until a physical model/firmware and its features have been tested.

**Xiaomi Mi TV 4A is the first priority test target.** Its exact hardware revision, Android/firmware version and enabled debugging services must be recorded before marking it Verified.

## Build

GitHub Actions builds a debug APK on pushes to main and manual workflow runs. Download the artifact named rext-remote-debug from a successful run.

- JDK 17
- Android SDK platform 36
- Gradle 9.6.0

## Research

See docs/architecture.md and docs/research.md. External repositories are used for protocol study, not for copying their UI. Source code is reused only when the license and attribution requirements are compatible and recorded.

## License

To be decided before public implementation release.
