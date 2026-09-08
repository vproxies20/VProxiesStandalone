# VProxies SA 1.2.1 — Standalone multi-proxy routing for Windows

VProxies SA is an independent Windows proxy-routing client. It does not contain, derive from, or redistribute Proxifier code or drivers.

VProxies SA has a separate application identity from the packaged-proxy edition: it installs to `C:\Program Files\VProxiesSA`, runs as `VProxiesSA.exe`, and stores settings in `%LocalAppData%\VProxiesSA`. Both editions can therefore be installed side by side without overwriting each other.

## V1 scope

- Fully local operation: no VProxies API, account, login, subscription, Gateway, telemetry, or automatic update request.
- User-managed HTTP, HTTPS, SOCKS4A, and SOCKS5 proxy servers.
- Multiple proxy outbounds in one routing engine, so different applications can use different proxies at the same time.
- Per-application actions: any saved proxy, `Direct (this PC IP)`, or `Block connection`.
- Configurable default action for applications without an explicit rule.
- Real proxy handshake checker with latency measurement.
- Proxy passwords encrypted locally with Windows DPAPI for the current Windows account.
- Optional DNS leak protection through the proxy, with a saved custom IPv4/IPv6 DNS server; disabled by default.
- Native WPF interface with the official VProxies logo and English-only copy.
- Minimize to the Windows notification area. Tray `Exit` always asks for confirmation.
- Clean shutdown of the complete sing-box process tree and removal of temporary configurations.
- Single-instance protection and no CMD/PowerShell/Python window at runtime.

## Routing example

| Application | Action |
|---|---|
| Chrome | Proxy 1 |
| Telegram | Proxy 2 |
| Game | Direct (this PC IP) |
| Untrusted app | Block connection |
| Every app without a rule | Configurable default action |

All rules are applied concurrently by one sing-box TUN engine. A proxy connection is opened only when matching application traffic needs it.

## Build on Windows

Requirements:

1. Windows 10/11 x64.
2. .NET 8 SDK.
3. Inno Setup 6.
4. Internet access during the first build, or pre-verified offline runtime files.

Run PowerShell in the repository directory:

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\build-windows.ps1
```

The installer is generated at:

```text
artifacts\VProxiesSASetup-1.2.1-win-x64.exe
```

## GitHub Actions

The `Build VProxies SA Windows Installer` workflow builds the installer, generates a SHA-256 file, uploads the build artifact, and publishes release `v1.2.1` from the `main` branch.

## Runtime and security notes

- The application requires administrator permission because TUN routing modifies Windows network routes.
- The generated configuration always includes explicit `direct` and `block` outbounds.
- VProxies SA and sing-box processes, private network destinations, and literal upstream proxy IPs bypass the TUN to prevent routing loops.
- Source credentials exist only in memory and in the temporary sing-box configuration. The temporary file is deleted immediately after startup and again during shutdown cleanup.
- Use only proxy servers that you own or are authorized to use.

## Pinned networking components

| Component | Version | Archive SHA-256 |
|---|---:|---|
| sing-box | 1.14.0 | `3ffb56267da14e287be48bd10cf7e6505260125bad940b75101fbb4d5d58e5d6` |
| Wintun | 0.14.1 | `07c256185d6ee3652e09fa55c0b673e2624b565e02c4b9091c79ca7d2f24ef51` |

Read `THIRD-PARTY-NOTICES.md` before distributing a compiled installer.
