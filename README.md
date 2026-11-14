# REXT Remote

A modern Android TV remote built around a minimal monochrome outline interface.

> Project status: early architecture and compatibility research.

## Product direction

REXT Remote has three user-selected connection methods:

1. **Bluetooth** — use Bluetooth HID where the TV supports it.
2. **Phone hotspot** — connect the TV and phone through the phone's hotspot network.
3. **Shared Wi-Fi** — connect both devices on the same local network.

These are connection choices inside the selected TV's **Connect** flow, not three app navigation tabs. After a method is selected, the app returns to the TV list and shows the icon for the active connection type.

The app's three swipe-navigated sections are:
- Device connection / TV list
- Remote
- Mouse

The UI follows the supplied reference: black background, white rounded outline controls, a circular D-pad with OK, and a square outline touch surface for mouse mode. The navigation indicators adapt their placement to the active screen.

## Initial technical direction

- Kotlin
- Jetpack Compose
- Material 3, with a deliberately minimal custom monochrome visual language
- Modular connection providers and a capability-based device catalog
- Local discovery where the selected protocol supports it
- Encrypted local storage for pairing credentials

## Important connection constraints

Bluetooth HID, Android TV Remote v2, ADB, Samsung SmartThings remote control, LG webOS SSAP, and Roku ECP are different protocols. A single generic connection implementation cannot control every TV brand.

A phone hotspot is a network topology, not a TV control protocol. The TV must join the phone's hotspot; the app cannot universally force that connection or guarantee that every TV will auto-join it.

Initial ADB authorization normally requires the user to approve the debugging key on the TV. Android TV Remote v2 pairing normally requires the on-screen pairing code. REXT Remote must explain these first-time authorization steps rather than claim silent access.

## Compatibility policy

The compatibility catalog will distinguish:
- Protocol/platform support
- Discovery support
- Pairing or authorization requirements
- Verified physical models
- Features such as D-pad, text input, and true pointer/mouse input

A brand name alone is not proof that every model from that brand is supported. Xiaomi Mi TV 4A is a priority test device; exact compatibility depends on its hardware revision, Android/firmware version, and enabled remote services.

See [docs/compatibility-matrix.md](docs/compatibility-matrix.md) and [docs/architecture.md](docs/architecture.md).

## Research references

Open-source projects used for protocol research (not UI copying):
- [theharisshah/androidremote](https://github.com/theharisshah/androidremote) — Android TV Remote v2, mDNS discovery, pairing, verified against a Xiaomi Mi TV.
- [levhakobyan/OpenTV-Remote](https://github.com/levhakobyan/OpenTV-Remote) — provider-based architecture, Bluetooth HID, Android TV, Samsung and webOS protocols.
- [Atharok/BtRemote](https://gitlab.com/Atharok/BtRemote) — Bluetooth HID remote and mouse/keyboard support.
- [arman0824/Universal-TV-Remote](https://github.com/arman0824/Universal-TV-Remote) — multi-platform discovery and network protocol notes.
- [Ayaan48/universal-tv-remote](https://github.com/Ayaan48/universal-tv-remote) — Android TV Remote v2 and Fire TV ADB paths.

Before reusing any source code, dependency, or protocol implementation, its license and attribution requirements must be reviewed.

## License

To be decided before publishing implementation code.
