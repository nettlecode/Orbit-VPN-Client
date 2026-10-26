# Orbit VPN

Android VPN client inspired by Shadowrocket. It runs on [sing-box](https://github.com/SagerNet/sing-box)
and embeds [Xray-core](https://github.com/XTLS/Xray-core) for XHTTP. It has subscriptions,
rule-based routing and a clean Material 3 UI.

## Download

Signed APKs are on the [Releases](https://github.com/nettlecode/Orbit-VPN-Client/releases)
page. Most phones need `Orbit-<version>-arm64-v8a.apk`; `universal` also covers x86_64.
New versions install over the old one, and the app offers them itself: it checks GitHub once a day,
downloads the APK for your device, verifies its checksum and opens the system installer.

To verify a download, check `SHA256SUMS` and the signing certificate. Its SHA-256 is
`49:BF:1F:7E:B3:5B:A7:E6:7F:24:20:17:7A:BB:60:0B:83:E3:E5:86:F7:6C:E2:A0:32:A1:62:4C:20:41:80:A3`:

```sh
apksigner verify --print-certs Orbit-<version>-arm64-v8a.apk
```

## Features

- **Protocols**: Shadowsocks (with plugins), VMess, VLESS (Reality, XHTTP), Trojan, Hysteria2,
  TUIC, SOCKS, HTTP. Transports: WebSocket, gRPC, HTTP/2, HTTPUpgrade, XHTTP.
- **Import**: share links, QR codes, clipboard, base64 subscriptions. Subscriptions show
  traffic usage and expiry from `subscription-userinfo` and update automatically.
- **Auto-select**: connects through the fastest of your favorite servers (or of all servers) and
  switches within seconds when it stops working.
- **Stays connected**: restores the connection after a restart or an app update, and works with
  Android's always-on VPN and "Block connections without VPN" as a kill switch.
- **Routing modes**: Rule-based, Global and Direct.
- **Rule configs**: a built-in config plus your own, or configs imported from Shadowrocket
  `.conf` files by URL or from a file. `RULE-SET`/`DOMAIN-SET` lists are downloaded and
  converted, and `IP-ASN` rules are supported.
- **Rules**: by domain, keyword, IP range, ASN, GeoIP country or app. "My rules" apply on top
  of the active config.
- **Real latency test**: an HTTPS request through each server. It gives the same result with
  the VPN on or off and works for UDP-based protocols too.
- **Traffic**: live speed chart, hourly, daily and monthly history, active connections with
  the option to close them.
- **Settings**: remote DNS (DoH, DoT, DoQ, HTTP/3), optional IPv6. GeoIP and ASN lists come
  from GitHub, jsDelivr or your own URL, with a chosen update interval.
- **Backup**: export and restore servers, subscriptions, rules and settings as one file,
  optionally encrypted with a password (AES-256-GCM).
- **Shortcuts**: home-screen widget and Quick Settings tile.
- **Languages**: English and Russian.

## Security

Orbit never opens an unauthenticated local proxy port. Several popular clients expose one, and
any app on the device can then use it to discover the server's IP, even an app excluded from
the VPN. Orbit avoids this:

- sing-box only has the TUN inbound.
- The hop to the embedded Xray-core uses Shadowsocks 2022 with a random key generated on every
  start. Both TCP and UDP are authenticated.
- No Clash API or Xray API is exposed.
- The control channel is a unix socket in the app's private directory.

## Building

Requirements: JDK 17+, Android SDK with NDK (tested with 28.2.13676358), Go 1.27+ and
[gomobile](https://github.com/sagernet/gomobile) v0.1.13.

1. Put the core sources next to the project. `core/go.mod` points to them through `replace`
   directives. The versions match the ones in the
   [release workflow](.github/workflows/release.yml).

   ```sh
   git clone --branch v1.14.3 https://github.com/SagerNet/sing-box third_party/sing-box
   git clone --branch v26.9.30 https://github.com/XTLS/Xray-core third_party/xray-core
   ```

2. Build the native library. This produces `app/libs/libbox.aar` with sing-box and the Xray
   bridge in one AAR.

   ```sh
   cd core
   go install github.com/sagernet/gomobile/cmd/gomobile github.com/sagernet/gomobile/cmd/gobind
   ./build.sh
   ```

3. Build and test the app:

   ```sh
   ./gradlew :app:assembleDebug        # APKs per ABI in app/build/outputs/apk/debug/
   ./gradlew :app:testDebugUnitTest
   ```

Requirements: minimum Android 7.0 (API 24); compile SDK 37, target SDK 36.

### Release builds

Signed release APKs need a keystore. Put its details in `keystore.properties` in the project
root; the file is git-ignored.

```properties
storeFile=/path/to/release.jks
storePassword=…
keyAlias=…
keyPassword=…
```

```sh
./gradlew :app:assembleRelease -PorbitVersion=1.2.3   # versionCode becomes 10203
```

Pushing a `v*` tag runs the [release workflow](.github/workflows/release.yml). It builds the
core and signed APKs and attaches them to a GitHub Release. It needs four repository secrets:
`ORBIT_KEYSTORE_BASE64`, `ORBIT_KEYSTORE_PASSWORD`, `ORBIT_KEY_ALIAS` and `ORBIT_KEY_PASSWORD`.

Debug builds use the package `app.orbit.vpn.debug`, so they install next to a release build.

## Project layout

| Path | Contents |
| --- | --- |
| `core/` | Go bridge: Xray lifecycle, socket protection and the latency test; `build.sh` for the AAR |
| `app/.../model`, `parser` | Nodes, rules, share-link and Shadowrocket config parsers |
| `app/.../config` | sing-box and Xray JSON config generation |
| `app/.../data` | Storage, subscription and rule-list downloads, backups, traffic history |
| `app/.../service` | `VpnService` integration with libbox |
| `app/.../ui`, `widget` | Jetpack Compose screens, Glance widget, Quick Settings tile |

Developer notes for working on the code are in [CLAUDE.md](CLAUDE.md).

## License

Orbit links sing-box, which is licensed under **GPL-3.0**, so distributed builds of the app
must be GPL-3.0 as well. Xray-core is licensed under MPL-2.0.

Orbit is an independent project. It is not affiliated with Shadowrocket and uses none of its
code or assets.
