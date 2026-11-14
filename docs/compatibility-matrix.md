# TV compatibility catalog

This is an intentionally broad discovery catalog. A listed brand or family is a candidate, not a promise that every model works. Compatibility depends on model year, operating system, firmware, region, enabled services and pairing policy.

## Status legend

- Candidate: family is worth probing; no model-level test recorded.
- Protocol detected: a compatible service was discovered.
- Tested: a physical model completed a connection test.
- Verified: listed capabilities were tested and documented on the exact model/firmware.

No brand below is marked Verified yet.

## Priority target device

| Reported model | Product family | Device codename | Android | Board / ABI | Status |
|---|---|---|---|---|---|
| MiTV-MSSP0 | Xiaomi Mi TV 4A 32 | amelie | Android 9 / API 28 | m5621, armeabi-v7a | Priority test candidate. MiLink discovery and TCP 6091 transport are implemented, but this exact firmware has not been physically tested. |


## Android TV / Google TV families

Implemented network provider: Android TV Remote v2 over local network (mDNS discovery, six-character on-screen pairing, TLS/protobuf control). MiLink is an additional Xiaomi-specific provider. ADB is an optional advanced path. Exact model and firmware support remains unverified until physically tested.

| Brand / family | Candidate models / product lines | Status | Notes |
|---|---|---|---|
| Xiaomi | MiTV-MSSP0 / Mi TV 4A 32 (amelie), Mi TV 4S, Mi TV 4X, Mi TV P1, Xiaomi TV A / Q series, Mi Boxes | Candidate | MiLink is attempted when the Xiaomi Cast service is advertised and TCP 6091 is reachable. Exact model/firmware testing is still required. |
| Sony | BRAVIA Android TV / Google TV | Candidate | Remote service availability varies by generation. |
| TCL | Android TV / Google TV models | Candidate | Do not assume every TCL model runs the same platform. |
| Hisense | Android TV / Google TV models | Candidate | VIDAA models use a different provider. |
| Philips | Android TV / Google TV models | Candidate | Saphi models are separate. |
| Sharp | Android TV models | Candidate | Verify Remote Service. |
| NVIDIA | SHIELD TV / SHIELD TV Pro | Candidate | Android TV Remote v2 candidate. |
| Google | Chromecast with Google TV / Google TV Streamer | Candidate | Android TV Remote v2 candidate. |
| Nokia | Android TV televisions / streaming boxes | Candidate | Model-specific. |
| Thomson | Android TV televisions / streaming devices | Candidate | Model-specific. |
| Toshiba | Android TV models | Candidate | Toshiba also sells other platforms. |
| JVC | Android TV models | Candidate | Model-specific. |
| Panasonic | Android TV models | Candidate | Only Android TV variants. |
| Skyworth | Android TV / Google TV models | Candidate | Model-specific. |
| Haier | Android TV models | Candidate | Model-specific. |
| Motorola | Android TV televisions / boxes | Candidate | Model-specific. |
| Blaupunkt | Android TV models | Candidate | Model-specific. |
| Metz | Android TV models | Candidate | Model-specific. |
| Grundig | Android TV models | Candidate | Model-specific. |
| Changhong | Android TV models | Candidate | Model-specific. |
| Konka | Android TV models | Candidate | Model-specific. |
| Akai | Android TV models | Candidate | Model-specific. |
| Westinghouse | Android TV models | Candidate | Model-specific. |
| RCA | Android TV models | Candidate | Model-specific. |
| Onn | Google TV / Android TV streaming devices | Candidate | Device family, not a universal TV-brand claim. |
| Mecool | Android TV boxes | Candidate | Include as external TV devices. |
| Formuler | Android TV boxes | Candidate | Include as external TV devices. |
| Homatics | Android TV / Google TV boxes | Candidate | Include as external TV devices. |

## Other smart-TV platforms

These need separate protocol providers. They are not Android TV Remote v2 devices.

| Brand / platform | Candidate families | Protocol candidate | Status |
|---|---|---|---|
| Samsung | Tizen Smart TV | Samsung local remote / WebSocket pairing | Candidate |
| LG | webOS TV | SSAP WebSocket + pairing/pointer channel | Candidate |
| LG | legacy NetCast | Legacy local remote protocol | Candidate |
| Roku | Roku TV / Roku players | ECP over local network | Candidate |
| Amazon | Fire TV | Android TV-like remote only on selected models; ADB path where enabled | Candidate |
| Hisense | VIDAA | VIDAA local control API where available | Candidate |
| Panasonic | My Home Screen | Model-specific local API | Candidate |
| Vizio | SmartCast | Local SmartCast API where available | Candidate |
| Philips | Saphi | Model-specific API | Candidate |
| Apple | Apple TV | Separate platform; not a TV-brand protocol | Not in initial scope |

## Bluetooth HID target families

Bluetooth HID is an independent transport choice. Candidate target families include Android TV / Google TV, Fire TV, and any TV that explicitly supports Bluetooth keyboard/mouse HID input. A Bluetooth menu in TV settings is not sufficient evidence: audio-only Bluetooth support does not prove HID host support.

## Capability verification checklist

For every physical test, record:
- Exact model code and model year.
- OS/platform and firmware version.
- Connection choice used (Bluetooth HID, phone hotspot, shared Wi-Fi).
- Discovery method and pairing/authorization steps.
- D-pad, OK, Back, Home, Menu.
- Volume/media keys and text input.
- Mouse pointer visibility, pointer movement, click, long-press and drag.
- Reconnection after app restart and TV standby.

## First test target

Xiaomi Mi TV 4A is the priority device. Do not mark it Verified until the exact variant and firmware are tested. Android TV Remote v2 is implemented as a network provider; Bluetooth HID remains an independent connection method. Neither makes an untested model Verified.


## Provider implementation status

| Provider | Current controls | Limitations |
|---|---|---|
| Xiaomi MiLink | Discovery, TCP 6091, D-pad, OK, Back, Home and Menu | No system mouse pointer or app inventory through this provider. Exact MiTV-MSSP0 test pending. |
| ADB TCP / Wireless debugging | Keys, tap/swipe, Leanback app discovery and launch | Requires network ADB and TV-side authorization. Android 9 firmware may require a separate network-ADB option. |
| Bluetooth HID | Phone as keyboard and relative mouse | TV must act as HID host and pair from its own Bluetooth accessory menu. |
| Android TV Remote v2 | mDNS discovery, six-character pairing over TLS 6467 and control over TLS 6466 | Implemented in the app. Exact model/firmware testing is still required. No standard pointer or installed-app inventory through this provider. |
| Samsung Tizen / LG webOS / Roku / VIDAA | Candidate platforms | Separate protocol providers and model-level tests required. |

