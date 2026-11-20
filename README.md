# UsqueBox

Android client for [usque](https://github.com/justinwoo280/usque) — a Cloudflare WARP MASQUE tunnel client.

## Features

- **MASQUE tunnel** via Cloudflare WARP (QUIC/HTTP3)
- **In-app registration** — register WARP account directly on device
- **Per-app proxy** — Global / Bypass / Proxy-Only modes
- **Configurable** — congestion control (BBR/Brutal/Reno), noise injection, keepalive, etc.
- **IPv4/IPv6 toggle** — disabled stacks are kernel black-holed (no leak)
- **Configuration editor** — import full or legacy flat usque JSON, configure local TUN MTU and DNS independently of QUIC and tunnel PMTU settings, and edit WARP endpoints. Saving preserves unedited JSON keys.
- **English and Simplified Chinese** — follows the device language; on Android 13+ the app language can also be chosen in system settings.
- **V1+V2+V3 APK signing** for release builds

## Build

### Prerequisites

- Go 1.25+
- JDK 21
- Android SDK (API 35, NDK 28.1)
- gomobile (`go install golang.org/x/mobile/cmd/gomobile@latest`)

### One-command build

```bash
./build-all.sh
```

This will:
1. Fetch usque source at the pinned commit (`usque.ref`)
2. Compile the Go mobile package into an AAR
3. Build the Android APK

### Manual steps

```bash
./fetch.sh        # Clone/checkout usque at pinned ref
./build.sh        # Compile AAR via gomobile
./gradlew assembleDebug   # Or assembleRelease with signing
```

To test a locally modified or not-yet-pushed core in the sibling `../usque`
checkout, run `./build.sh` directly (without fetching or checking out
`usque.ref`), followed by `./gradlew testDebugUnitTest assembleDebug`. The PMTU
echo-target setting requires a core with CONNECT-IP echo probing support.

### Configuration editor

Use **Load JSON** to populate the form before editing. Imports that are not TUN
configurations are rejected. Android-specific settings (app rules) stay in the
app, and desktop-only JSON keys stay intact without being applied by Android.
The VPN uses the saved settings on the next connection. The TUN IPv4/IPv6
switches affect applications in the VPN; **Reach WARP over IPv6** controls
the independent outer connection. DNS addresses for a disabled family are
retained in the JSON but not advertised to Android while that family is off.

APK builds compress native libraries by default, which substantially reduces
the distributable size of the Go runtime. To build with page-aligned,
uncompressed `.so` files instead, use:

```bash
./gradlew assembleRelease -PcompressNativeLibs=false
```

### Signing

Create `keystore.properties` for signed release builds:

```properties
KEYSTORE_FILE=usquebox-release.jks
KEYSTORE_PASS=your-password
ALIAS_NAME=usquebox
ALIAS_PASS=your-key-password
```

Or set equivalent environment variables (`KEYSTORE_BASE64`, `KEYSTORE_PASS`, `ALIAS_NAME`, `ALIAS_PASS`).

Without signing credentials, the build falls back to unsigned debug APK.

## CI

| Workflow | Trigger | Signing |
|----------|---------|---------|
| **Preview** | Manual (Actions tab) | Optional — signed if secrets present |
| **Release** | Manual with version input | Required — fails without secrets |

### Required Secrets

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded .jks keystore |
| `KEYSTORE_PASS` | Keystore password |
| `ALIAS_NAME` | Key alias |
| `ALIAS_PASS` | Key password |

## Updating usque version

Edit `usque.ref`:

```
USQUE_REPO=https://github.com/justinwoo280/usque.git
USQUE_REF=<commit-hash-or-tag>
```

Then run `./build-all.sh`.

## Project Structure

```
usquebox/
├── usque.ref              # Pinned usque commit
├── fetch.sh               # Fetch usque source
├── build.sh               # Build AAR via gomobile
├── build-all.sh           # Full pipeline
├── app/
│   ├── build.gradle.kts   # Android build config + signing
│   └── src/main/kotlin/com/usquebox/
│       ├── MainActivity.kt
│       ├── service/       # VpnService bridge
│       ├── viewmodel/     # State management
│       ├── data/          # ConfigStore, AppManager
│       └── ui/            # Jetpack Compose screens
└── .github/workflows/     # CI
```

## License

Same as [usque](https://github.com/justinwoo280/usque).
