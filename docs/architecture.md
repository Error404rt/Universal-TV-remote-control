# Architecture

## Product invariants

- The supplied monochrome outline reference is the UI specification.
- No Material Design components, Material icon pack, filled controls, shadows or decorative surfaces.
- The three connection methods live inside the **Connect** action: Bluetooth HID, phone hotspot + local protocol, shared Wi-Fi + local protocol.
- These are not the three swipe destinations.
- Swipe destinations are Touchpad, Remote and Devices. Swiping right from Remote opens Touchpad; swiping left opens Devices.
- The selected connection method determines the left connection indicator: Bluetooth, phone or Wi-Fi.

## Source layout

| Area | File |
|---|---|
| Activity and state orchestration | `app/src/main/java/com/universalremote/app/MainActivity.kt` |
| Pages, sheets and touch surface | `RemoteScreens.kt` |
| Outline theme, icons and primitives | `OutlineUi.kt` |
| Discovery | `connection/TvDiscovery.kt` |
| Domain models and candidate merge | `connection/TvModels.kt` |
| Saved TVs | `connection/SavedTvStore.kt` |
| Android TV Remote v2 | `connection/AndroidTvRemoteV2.kt` |
| ADB | `connection/AdbRemoteClient.kt` |
| Bluetooth HID | `connection/BluetoothHidRemote.kt` |
| Wire codec / pairing digest | `connection/RemoteWire.kt`, `connection/PairingCrypto.kt` |
| Network and hotspot state | `connection/NetworkUtils.kt`, `connection/NetworkStatus.kt` |
| Curated app catalog | `connection/TvAppCatalog.kt` |

## UI

Jetpack Compose Foundation + Canvas are used for layout, gestures and drawing. Every visible control is custom outline artwork.

- Devices: saved/discovered `TvCandidate` rows, Connect at upper right, manual IP, refresh and status.
- Remote: outlined circular D-pad, OK, Menu, Back, Home and up to three quick-app shortcuts with a plus control.
- Touchpad: rounded-square outline surface, left/right/back actions and a connection-specific hint.
- The bottom bar keeps the connection icon on the left and three page indicators in the center.
- The active page is a small dot; inactive pages show outline icons.

## Domain

- `TvEndpoint`: one protocol endpoint (`host`, `port`, `TvEndpointKind`).
- `TvProtocol`: `ANDROID_TV_REMOTE`, `ADB`, `BLUETOOTH_HID`.
- `TvCandidate`: one physical TV, merging a host's endpoints and optional saved entry.
- `SavedTv`: persisted name, host, port, protocol and connection method.
- `ConnectionMethod`: `BLUETOOTH`, `HOTSPOT`, `WIFI`.
- `TvApp`: package name, display name, optional component name.

`buildTvCandidates` merges endpoints and saved TVs by host. Saved TVs remain selectable while offline.
`preferredEndpoint()` prefers Android TV Remote, then ADB, then Bluetooth.

## Discovery

- DNS-SD via `NsdManager` for `_androidtvremote2._tcp` and `_adb._tcp`.
- NsdManager resolutions are serialized because many Android versions allow only one resolve at a time.
- A bounded private IPv4 subnet probe checks TCP 6466 (Android TV Remote) and TCP 5555 (ADB).
- Saved hosts are probed explicitly even when their subnet is no longer scanned.
- Only RFC1918/private and CGNAT addresses are accepted. Probe count and concurrency are capped.
- mDNS callbacks are lossy; rows stay visible until refresh instead of flickering on service-lost events.
- Every mDNS-advertised endpoint is verified with a short TCP probe before it is shown, because some firmware advertises a service while its port is closed.

A discovered TCP port is only a candidate. ADB requires a shell handshake and TV-side authorization. Android TV Remote requires successful pairing/control state.

## Connection flow

1. User chooses Connect → one of three methods.
2. Bluetooth starts HID registration and phone discoverability.
3. Hotspot/Wi-Fi refresh network state, open system settings if needed, then scan.
4. Tapping a candidate connects using its saved method or the pending method.
5. Android TV Remote may ask for the six-character TV code. If its pairing/control port refuses the connection and the same TV exposes ADB, the app retries over ADB automatically.
6. Successful connections are saved with the selected method.
7. Switching TV disconnects the previous transport without discarding saved pairings.

## Android TV Remote v2

- Pairing: TLS 1.2 on TCP 6467, RSA client identity from the Kadb-compatible key store.
- SHA-256 pairing digest and six-character code validation live in `PairingCrypto`.
- After pairing, the TV certificate SHA-256 fingerprint is pinned per host.
- Control: TLS 1.2 on TCP 6466, protobuf frames encoded by `RemoteWire`.
- Supported input: D-pad, OK, Back, Home, Menu, app links.
- Not supported by the protocol in this implementation: installed-app inventory, system pointer, touch coordinates.
- On the touchpad, taps become OK and swipes become D-pad focus movements.

## ADB provider

- Kadb 1.3.0 (Apache-2.0) over classic TCP ADB.
- The host key is encrypted with AES-GCM and a non-exportable Android Keystore key.
- First connection is authorized by the TV's own RSA prompt.
- Supported: key events, tap/swipe, installed Leanback activities and app launch.
- Android 11+ wireless-debugging pair/connect endpoints are intentionally not implemented; the UI does not claim them.

## Bluetooth HID provider

- The phone registers as a HID keyboard + relative mouse + consumer-control device.
- Pairing starts from the TV's Bluetooth accessory menu.
- Android may unregister a HID app when it leaves the foreground; keep the app open during pointer use.
- A real cursor appears only when TV firmware supports HID mouse input.

## Hotspot vs shared Wi-Fi

Both use the same local protocol providers after discovery. Only the network topology differs.

- Hotspot: TV joins the phone's tethering network. The TV must already know the credentials.
- Shared Wi-Fi: both devices are on the same router network.

Android's public API cannot toggle a hotspot. `HotspotController` opens the system tethering screen; `NetworkStatus` reports hotspot/Wi-Fi state and local addresses.
The app never claims it can force a TV onto the hotspot or read its password.

## Saved devices and quick apps

- `SavedTvStore` persists up to 16 TVs as JSON in SharedPreferences.
- Only network identity and selected method are stored; ADB/TLS secrets remain in their own stores.
- Quick-app package IDs are stored per host and capped at three.
- ADB supplies the real installed-app list. Android TV Remote uses a curated fallback because the protocol cannot enumerate apps.

## Security and privacy

- Pairing pins and ADB identities are stored in Android Keystore-backed storage.
- Pairing codes, private keys and command payloads are not logged.
- Only local private IPv4 ranges are scanned, with capped host count/concurrency.
- Bluetooth permissions are requested only when Bluetooth is selected.
- No cloud relay is required.

## Compatibility evidence

A catalog entry is a candidate until a physical model has been tested. A model becomes Verified only after its platform/firmware and listed capabilities are recorded. Xiaomi Mi TV 4A is the first target but remains unverified until tested.
