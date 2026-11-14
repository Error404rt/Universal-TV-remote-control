# REXT Remote

A modern Android TV remote built around a minimal monochrome outline interface.

> Project status: early prototype. UI skeleton and compatibility research are committed; device discovery and real control providers are not implemented yet.

## Product direction

REXT Remote has three user-selected connection methods inside the selected TV's Connect flow:

1. **Bluetooth HID** — the phone acts as a Bluetooth keyboard/mouse/remote when the TV supports Bluetooth HID host input.
2. **Phone hotspot** — the TV and phone share the phone's hotspot network; the selected TV protocol still performs control.
3. **Shared Wi-Fi** — the phone and TV are already on the same local network.

These are connection choices, not app navigation tabs. After a method is selected, the app returns to the TV list and shows the icon for the active connection type.

The app's three swipe-navigated sections are:
- Devices / connection
- Remote
- Mouse

The UI follows the supplied reference: black background, white rounded outline controls, a circular D-pad with OK, Menu, Back and Home, and a square outline touch surface for mouse mode. The navigation indicators adapt their placement to the active screen.

## Current repository contents

- Initial Jetpack Compose application skeleton.
- First UI prototype for the D-pad, navigation indicators, device list and Connect method chooser.
- Initial broad TV compatibility catalog.
- Architecture and protocol research notes.
- GitHub Actions workflow to build and upload a debug APK.

The current UI is a prototype. The displayed device is placeholder data; it does not discover or control a TV yet.

## Build

GitHub Actions builds a debug APK on pushes to main and manual workflow runs. Download the artifact named rext-remote-debug from the workflow run.

Local build requirements:
- JDK 17
- Android SDK platform 36
- Gradle 9.6.0

## Research and compatibility

See:
- [Architecture](docs/architecture.md)
- [TV compatibility catalog](docs/compatibility-matrix.md)
- [Open-source protocol research](docs/research.md)

Xiaomi Mi TV 4A is the first priority test target. Model-level support will be marked Verified only after a real connection and capability test.

## License

To be decided before publishing implementation code.
