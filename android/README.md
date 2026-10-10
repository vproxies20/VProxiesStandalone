# VProxies SA 1.4.0 for Android

Android edition of VProxies SA: a fully local proxy-routing client with no account, API, telemetry or update requests. It uses the same routing core as the Windows edition (sing-box 1.14.0), embedded through `libbox` and Android's `VpnService` — no root required.

## Features

- HTTP, HTTPS (with optional SNI), SOCKS4A and SOCKS5 proxy servers you manage yourself.
- Several proxies active at once in one routing engine.
- Per-app actions by Android package name: any saved proxy, `Direct (this device IP)`, or `Block connection`.
- Configurable default action for every app without a rule.
- Real proxy handshake checker with latency (`CONNECT www.cloudflare.com:443`), same as Windows.
- Optional DNS through the proxy (leak protection) with a custom IPv4/IPv6 DNS server; off by default.
- IPv4 and IPv6 traffic both go through the tunnel, so IPv6-capable apps cannot bypass a proxy rule.
- Proxy passwords encrypted with an AES-256-GCM key held in the Android Keystore; saving passwords can be turned off.
- System, Light and Dark themes in the VProxies cyan–purple palette; sidebar layout on tablets, foldables and Samsung DeX.
- Activity log with credential masking, copy and clear.
- JSON import/export. Windows exports can be imported: proxies, default action, DNS and theme carry over; Windows `.exe` rules and DPAPI-encrypted passwords cannot be used on Android and are reported during import.
- Foreground notification with a Stop action; Android's own "VPN" key icon shows while routing is active.

Requires Android 10 (API 29) or newer, ARM64 or ARMv7 devices.

## Build with GitHub Actions (recommended)

The workflow `.github/workflows/build-android.yml` runs on every push to `main` that touches `android/**`, or manually from the Actions tab:

1. Builds `libbox.aar` from the official `sing-box` `v1.14.0` source with sing-box's own build script (cached after the first run, which takes about 15–25 minutes).
2. Builds the release APK with Gradle.
3. Uploads `VProxiesSA-1.4.0-android.apk` and its SHA-256 as a workflow artifact.
4. When a signing key is configured, attaches the APK to the `v1.4.0` GitHub release next to the Windows installer.

### Release signing (do this once)

Without a signing key the APK is signed with a temporary debug key that changes on every runner, so a later build cannot update an installed copy. Create a permanent key once and keep a backup of it — losing it means users must uninstall to update.

```bash
keytool -genkeypair -v -keystore vproxies-release.jks -alias vproxies \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 vproxies-release.jks > vproxies-release.jks.b64   # macOS: base64 -i vproxies-release.jks
```

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | contents of `vproxies-release.jks.b64` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `vproxies` |
| `ANDROID_KEY_PASSWORD` | key password |

## Build locally

Requirements: JDK 17, Android SDK (platform 35), Go 1.26, Android NDK r28, and the sing-box gomobile tools.

```bash
git clone --depth 1 --branch v1.14.0 https://github.com/SagerNet/sing-box.git
cd sing-box
make lib_install
export PATH="$PATH:$(go env GOPATH)/bin"
export ANDROID_NDK_HOME=/path/to/android-ndk-r28
go run ./cmd/internal/build_libbox -target android -platform android/arm64,android/arm
cp libbox.aar /path/to/VProxiesStandalone/android/app/libs/

cd /path/to/VProxiesStandalone/android
./gradlew assembleRelease
```

Or open the `android` folder in Android Studio after copying `libbox.aar` into `app/libs/`.

## Project layout

| Path | Purpose |
|---|---|
| `app/src/main/java/app/vproxies/sa/core/SingBoxConfigBuilder.kt` | Port of the Windows config builder; rules match `package_name` instead of `.exe` paths |
| `app/src/main/java/app/vproxies/sa/core/ProxyTester.kt` | Port of the Windows proxy handshake checker |
| `app/src/main/java/app/vproxies/sa/core/SettingsStore.kt` | Settings persistence and Windows-compatible JSON import/export |
| `app/src/main/java/app/vproxies/sa/core/SecretStore.kt` | Android Keystore password encryption (Windows: DPAPI) |
| `app/src/main/java/app/vproxies/sa/bg/VProxiesVpnService.kt` | VpnService hosting the sing-box core through libbox |
| `app/src/main/java/app/vproxies/sa/ui/` | Jetpack Compose interface: Proxies, Apps, Settings, Activity |

## Runtime notes

- Android allows one active VPN at a time; starting VProxies SA disconnects any other VPN app.
- VProxies SA excludes itself from the tunnel, so the proxy checker always measures the device's own connection.
- Private network destinations and literal upstream proxy IPs always go direct to prevent routing loops, as on Windows.
- Use only proxy servers that you own or are authorized to use.

## License note

The APK links sing-box's `libbox` (GPL-3.0-or-later) into the app. Distributing the APK therefore creates GPL obligations for the application as a whole, not only for an unmodified separate binary as on Windows. Read `../THIRD-PARTY-NOTICES.md` and obtain legal review before commercial distribution.
