# Open-source protocol research

Research is for protocol and architecture understanding only. REXT Remote keeps its own UI and product behavior. Before copying source code or adding a dependency, inspect its license and notices.

## Projects reviewed

### theharisshah/androidremote
https://github.com/theharisshah/androidremote

- Android app for Android TV / Google TV.
- Uses Android TV Remote v2 over the local network.
- README describes mDNS discovery, manual IP fallback and the standard on-screen six-digit pairing flow.
- Project says it was built and verified against a Xiaomi Mi TV.
- Useful for the first Xiaomi / Android TV network-provider investigation.
- It explicitly does not rely on ADB or cloud services.

### levhakobyan/OpenTV-Remote
https://github.com/levhakobyan/OpenTV-Remote

- Multi-platform smart-TV remote.
- Repository describes Android TV Remote v2, webOS SSAP, Samsung local WebSocket control and Bluetooth HID.
- Useful architecture pattern: explicit provider per protocol, capability model, discovery module, encrypted credential vault.
- Repository states MIT license. Re-check current license file before reusing code.

### Ayaan48/universal-tv-remote
https://github.com/Ayaan48/universal-tv-remote

- Kotlin / Jetpack Compose Android remote for Google TV and Fire TV.
- Repository description identifies mDNS discovery, protobuf pairing over TLS, D-pad, keyboard and voice input.
- Useful additional reference for Android TV Remote v2 and Fire TV-specific paths.

### jqssun/android-bt-remote
https://github.com/jqssun/android-bt-remote

- Bluetooth Classic HID keyboard, mouse and media remote.
- Uses Android BluetoothHidDevice so the phone presents itself as an input device to a target that supports Bluetooth HID host.
- README identifies GPL-3.0. Do not copy code into a differently licensed project without resolving GPL obligations.
- Useful for evaluating the Bluetooth HID connection choice and mouse reports.

### Atharok/BtRemote
https://gitlab.com/Atharok/BtRemote

- Bluetooth remote for Android TV using HID, with mouse and keyboard support.
- F-Droid listing describes Kotlin, Jetpack Compose and Material 3, and identifies GPL-3.0-or-later.
- Source host is GitLab, not GitHub. Inspect upstream repository and license before any reuse.

## Protocol conclusions

1. Android TV Remote v2 is a local-network protocol with discovery and on-screen pairing. It is not Bluetooth.
2. Bluetooth HID makes the phone act as a keyboard/mouse/consumer-control device. The TV must support Bluetooth HID host input.
3. A phone hotspot only provides the local IP network. It does not replace Android TV Remote v2, Samsung, webOS, Roku or another vendor protocol.
4. Samsung, webOS, Roku and VIDAA need separate providers and pairing/authorization handling.
5. A D-pad working does not prove that a true system mouse pointer works. Pointer capabilities must be tested separately.

## Official Android implementation baseline

At research time, Android Developers documents Compose BOM 2026.09.00; the stable AndroidX release table lists Material 3 1.4.0. Use the stable BOM and stable libraries, not alpha/RC, unless a specific required API forces otherwise. Re-check before the first build.
