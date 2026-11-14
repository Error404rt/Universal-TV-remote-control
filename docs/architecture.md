# Architecture

## Product invariants

- The supplied monochrome outline reference is the UI specification.
- No Material Design components, Material icon pack, filled controls, shadows or decorative surfaces.
- The three connection methods live inside the selected TV's Connect action: Bluetooth HID, phone hotspot + a local TV protocol, and shared Wi-Fi + a local TV protocol.
- These are not the three swipe-navigation destinations.
- The app destinations are Devices, Remote and Mouse. Navigation uses horizontal swipes; indicators are not buttons.
- The selected connection method determines the TV's connection icon: Bluetooth, phone or Wi-Fi.

## UI

Jetpack Compose Foundation + Canvas are used for layout, gestures and drawing, but every visible control is custom outline artwork.

- Devices: discovered TV/ADB endpoints, Connect at the upper right, method chooser and connection status.
- Remote: outlined circular D-pad, OK, Menu, Back, Home and quick-launch app shortcuts with a plus control.
- Mouse: square rounded-outline touch surface and compact secondary controls.
- The three nav icons move to the top on Devices and Mouse, and remain under the remote buttons/shortcuts on Remote.
- The selected section is shown as a small dot; the two inactive sections show their outline icons.

## Domain

Core models:
- TvDevice: stable id, display name, manufacturer/model hints, address, platform hints.
- ConnectionMethod: BLUETOOTH_HID, PHONE_HOTSPOT, SHARED_WIFI.
- TvProtocol: ADB_TCP, ADB_TLS, ANDROID_TV_REMOTE_V2, WEBOS_SSAP, SAMSUNG_REMOTE, ROKU_ECP, BLUETOOTH_HID.
- TvCapabilities: dpad, back, home, menu, text input, media keys, volume, pointer, drag, long-press.
- PairingState: not-required, needs-user-confirmation, paired, failed.
- SupportEvidence: candidate, protocol-detected, tested-on-model, verified.

## Discovery

Use protocol-specific discovery:
- Kadb mDNS: _adb._tcp, _adb-tls-connect._tcp, _adb-tls-pairing._tcp.
- Bounded local IPv4 subnet probe for classic ADB TCP port 5555 when ADB mDNS is missing.
- Android TV Remote v2: _androidtvremote2._tcp discovery and its own pairing flow (separate provider, future stage).
- Samsung / LG / Roku: platform-specific local discovery (future providers).
- Bluetooth HID: phone advertises a HID input device; the TV initiates accessory discovery and pairing.

A found TCP port is only a candidate. REXT checks an ADB shell handshake before marking the TV connected.

## Connection providers

Each provider should implement a common interface:
- discover()
- pair(device)
- connect(device)
- send(command)
- disconnect()

Providers own protocol-specific pairing, transport, retries, timeouts and capability reporting. Never send Android TV protobuf frames to Samsung/webOS devices.

### ADB provider

The current implementation uses Kadb (Apache-2.0) for classic TCP ADB and Android 11+ wireless pairing/connect endpoints. The ADB host key is persisted encrypted with AES-GCM using Android Keystore. ADB authorization remains user-controlled by the TV's own RSA/wireless-debugging confirmation.

Supported commands in the current provider:
- Android key events for D-pad, OK, Back, Home and Menu.
- ADB tap/swipe input for touch-style fallback.
- Leanback-launchable app discovery and app launch.

ADB input tap/swipe does not create a system mouse cursor. It is a touch-event fallback, not a true pointer.

### Bluetooth HID provider

The phone registers as a Bluetooth HID keyboard + relative mouse + consumer-control device. The TV must act as the HID host and pair with the phone. The provider advertises actual HID connection state to the UI.

HID mouse input can provide a real cursor only when the TV firmware supports Bluetooth HID mouse input. Android may unregister a HID device app when it is no longer in the foreground, so the app should remain open during mouse use.

### Hotspot vs shared Wi-Fi

Phone hotspot and shared Wi-Fi use the same local TV protocol provider after network discovery. Their only difference is network topology:
- Phone hotspot: TV joins the phone's internet-sharing network.
- Shared Wi-Fi: both are already connected to the same router/network.

The app must not claim it can force a TV onto the hotspot or read/enter its Wi-Fi password. The TV must already know the credentials or be connected manually once.

## Input model

Normalize UI gestures into:
- KeyDown / KeyUp
- Tap
- LongPress
- PointerMove
- PointerButtonDown / PointerButtonUp
- TextInput

A provider advertises only the capabilities it can actually deliver. A D-pad working does not prove that a true pointer works.

## Security and privacy

- Pairing credentials and private ADB identities are encrypted with Android Keystore-backed storage.
- Do not log pairing codes, private keys, tokens or command payloads.
- Only scan local private IPv4 subnets and cap probe concurrency/host count.
- Request Bluetooth permissions only when the user chooses Bluetooth.
- Explain first-time ADB authorization and Bluetooth HID pairing.
- No cloud relay is required for local control.

## Compatibility evidence

A catalog entry is a candidate until a physical model has been tested. A model becomes Verified only after its firmware/platform and the listed capabilities have been recorded. Xiaomi Mi TV 4A is the first test target.
