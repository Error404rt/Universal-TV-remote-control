# Open-source protocol research

Research is for protocol and architecture understanding. Universal Remote Control keeps the user's original UI and the three product-specific connection choices. Before copying source code or adding a dependency, inspect its license and attribution requirements.

## Projects reviewed

### flyfishxu/Kadb
https://github.com/flyfishxu/Kadb

- Kotlin Multiplatform ADB client.
- Apache-2.0 license.
- Provides classic ADB TCP, Android 11+ wireless pairing, shell commands and optional mDNS discovery.
- The app uses Kadb for classic ADB TCP shell transport and Android's built-in NsdManager for `_adb._tcp` and `_androidtvremote2._tcp` discovery.
- Kadb can perform Android 11+ wireless pairing, but this app intentionally exposes only classic network ADB on port 5555; the TLS pairing endpoints are not used.
- ADB key persistence in Universal Remote Control uses its own Android Keystore-backed AES-GCM store.

### levhakobyan/OpenTV-Remote
https://github.com/levhakobyan/OpenTV-Remote

- Multi-platform smart-TV remote.
- Repository describes Android TV Remote v2, webOS SSAP, Samsung local WebSocket control and Bluetooth HID.
- Useful architecture pattern: explicit provider per protocol, capability model, discovery module and encrypted credential vault.
- Repository states MIT license. Re-check current license file before reusing code.

### theharisshah/androidremote
https://github.com/theharisshah/androidremote

- Android app for Android TV / Google TV.
- Uses Android TV Remote v2 over the local network.
- README describes mDNS discovery, manual IP fallback and the six-character pairing flow.
- Project says it was built and verified against a Xiaomi Mi TV.
- It does not use ADB or cloud services.
- No compatible source license was confirmed for direct code reuse; use as a protocol reference only unless its licensing is clarified.

### Ayaan48/universal-tv-remote
https://github.com/Ayaan48/universal-tv-remote

- Kotlin / Jetpack Compose Android remote for Google TV and Fire TV.
- Repository description identifies mDNS discovery, protobuf pairing over TLS, D-pad, keyboard and voice input.
- Useful additional reference for Android TV Remote v2 and Fire TV-specific paths.

### jqssun/android-bt-remote
https://github.com/jqssun/android-bt-remote

- Bluetooth Classic HID keyboard, mouse and media remote.
- Uses Android BluetoothHidDevice so the phone presents itself as an input device to a target that supports Bluetooth HID host.
- Repository identifies GPL-3.0. Do not copy code into a differently licensed project without resolving GPL obligations.
- Useful as a reference for HID report descriptors and mouse/keyboard input.

## Protocol conclusions

1. Android TV Remote v2 is a local-network protocol with discovery and on-screen pairing. It is not Bluetooth.
2. Bluetooth HID makes the phone act as a keyboard/mouse/consumer-control device. The TV must support Bluetooth HID host input.
3. A phone hotspot only provides the local IP network. It does not replace Android TV Remote v2, Samsung, webOS, Roku or another vendor protocol.
4. Samsung, webOS, Roku and VIDAA need separate providers and pairing/authorization handling.
5. ADB key events and ADB input tap/swipe do not create a system mouse cursor. A true cursor requires Bluetooth HID mouse support or a TV protocol that exposes pointer input.
6. First-time ADB approval is controlled by the TV. ADB must be exposed over classic TCP port 5555; this app does not implement Android 11+ wireless-debugging pairing.

## License policy

- Kadb is Apache-2.0.
- OpenTV-Remote is MIT.
- jqssun/android-bt-remote is GPL-3.0.
- The Android TV Remote v2 reference project has no direct-reuse license confirmed.

Universal Remote Control should not copy GPL code unless the project deliberately adopts compatible licensing. Any reused Apache/MIT code must retain license and attribution notices.

## Android implementation baseline

The build uses AGP 9 built-in Kotlin support and Compose Foundation/Canvas for a custom-drawn outline UI. Material Design components and icon packs are not used.
