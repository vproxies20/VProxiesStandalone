# Third-party notices

## sing-box 1.14.0

- Project: https://github.com/SagerNet/sing-box
- Exact source tag: https://github.com/SagerNet/sing-box/tree/v1.14.0
- Windows AMD64 release: https://github.com/SagerNet/sing-box/releases/tag/v1.14.0
- License: GNU General Public License version 3 or later, plus the project's naming restriction notice.

The VProxies build script downloads the official unmodified command-line binary. Distributing an installer containing it creates GPL source-code and notice obligations. Keep the exact corresponding source available and obtain legal review before commercial distribution. VProxies must not imply that it is the sing-box project or is endorsed by SagerNet.

## Wintun 0.14.1

- Project and download: https://www.wintun.net/
- Source: https://git.zx2c4.com/wintun

The official Wintun archive contains signed DLLs and the license applicable to those prebuilt files. The build script copies that license into the installer. Distribute only the unmodified, officially signed DLL from that archive; do not use an unofficial mirror build.

## sing-box 1.14.0 in the Android edition (libbox)

- Source: https://github.com/SagerNet/sing-box/tree/v1.14.0 (`experimental/libbox`), built unmodified with the project's own `cmd/internal/build_libbox` script by `.github/workflows/build-android.yml`.
- License: GNU General Public License version 3 or later, plus the project's naming restriction notice.

On Android the routing core is linked into the application package as `libbox.aar` instead of running as a separate executable. Distributing the APK therefore means distributing a combined work under the GPL: provide the complete corresponding source of the Android application and of sing-box, keep these notices, and obtain legal review before commercial distribution. The Android platform glue in `android/app/src/main/java/app/vproxies/sa/bg/` follows the public libbox interface used by the official sing-box for Android client (GPL-3.0).
