# Architecture

## Product invariants

- The three connection methods are choices inside the selected TV's Connect action: Bluetooth HID, phone hotspot + local protocol, and shared Wi-Fi + local protocol.
- They are not the three swipe-navigation destinations.
- The three app destinations are Devices, Remote, and Mouse. Navigation is gesture-driven; the small indicators are status/navigation hints, not buttons.
- The selected connection method determines the connection badge/icon shown for the TV: Bluetooth, phone, or Wi-Fi.
- UI follows the supplied monochrome outline reference. Do not replace it with generic filled Material buttons.

## Layers

### UI
Jetpack Compose screens:
- Device list and selected-device Connect sheet.
- Remote screen with circular D-pad, outlined OK, Menu, Back and Home.
- Mouse screen with a square outlined touch surface.
- Expressive loading/morphing indicator for discovery and connection states.

Material 3 is used for accessibility, typography, motion primitives and adaptive behavior. The product's black/white outline visual language remains custom and reference-led.

### Domain
Core models:
- TvDevice: stable id, display name, manufacturer/model hints, address, platform hints.
- ConnectionMethod: BLUETOOTH_HID, PHONE_HOTSPOT, SHARED_WIFI.
- TvProtocol: ANDROID_TV_REMOTE_V2, ADB, WEBOS_SSAP, SAMSUNG_REMOTE, ROKU_ECP, BLUETOOTH_HID.
- TvCapabilities: dpad, back, home, menu, text input, media keys, volume, pointer, drag, long-press.
- PairingState: not-required, needs-user-confirmation, paired, failed.
- SupportEvidence: candidate, protocol-detected, tested-on-model, verified.

### Discovery
Use protocol-specific discovery rather than one generic scan:
- Android TV Remote v2: mDNS service discovery; manual IP fallback.
- ADB: explicit TCP/IP endpoint only after developer settings and user authorization.
- Samsung / LG / Roku: their documented or reverse-engineered local network discovery where applicable.
- Bluetooth: Bluetooth discovery/pairing for HID-capable TV hosts.

Discovery results are hints, not proof of control compatibility. Verify the protocol handshake before marking a device as supported.

### Connection providers
Each provider implements a shared interface:
- discover()
- pair(device)
- connect(device)
- send(command)
- disconnect()

Providers own their protocol-specific pairing, transport, retries, timeouts and capability reporting. Never send Android TV protobuf frames to Samsung or webOS devices.

### Input model
Normalize UI gestures into commands:
- KeyDown / KeyUp
- Tap
- LongPress
- PointerMove
- PointerButtonDown / PointerButtonUp
- TextInput

A provider advertises only capabilities it can actually deliver. A TV that accepts D-pad keys is not automatically considered to support a true pointer.

## The three connection choices

### Bluetooth HID
The phone acts as a Bluetooth HID device (keyboard/mouse/consumer-control reports) when Android and the TV both support the required roles and profiles. This is not the same thing as pairing two ordinary Bluetooth audio devices. Verify target TV host support and Android BluetoothHidDevice availability.

### Phone hotspot
The hotspot is network transport topology, not a remote-control protocol. The TV must join the phone's hotspot. REXT can guide the user and discover the TV once both are on that network. It cannot universally make a TV join a hotspot or silently enter its saved Wi-Fi password.

### Shared Wi-Fi
Phone and TV are already on the same LAN. Discover and connect through the protocol supported by that TV. Android TV Remote v2 pairing and other vendor authorization flows may require confirmation on the TV.

## Security and privacy

- Pairing credentials and TLS identities must be encrypted with Android Keystore-backed storage.
- Do not log pairing codes, private keys, tokens or message payloads.
- Never scan outside the local network.
- Ask for nearby-device, Bluetooth and local-network permissions only when needed.
- Explain first-time pairing/ADB authorization on screen.
- No cloud relay is required for local control.

## Compatibility rules

The catalog lists candidate brands/families, not blanket guarantees. A model becomes Verified only after a real device test with a recorded firmware/platform and tested capabilities. Xiaomi Mi TV 4A is the first priority test target.
