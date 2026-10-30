# Orbit VPN

Android VPN client with the feature set of Shadowrocket (iOS). Shadowrocket is only a
functional reference: its name, icons, strings and other resources must not be used. App name **Orbit**, package `app.orbit.vpn`. The UI design follows the
"NEXUS VPN" mockup (green theme), but the name stays Orbit.

The user speaks Russian; UI strings exist in English (`values/`) and Russian (`values-ru/`),
and every new string needs both.

## Architecture

- **sing-box v1.14.3 (libbox)** is the main core: TUN, routing, DNS, all protocols except XHTTP.
- **Xray-core v26.9.30** runs alongside for XHTTP nodes (`ProxyNode.requiresXray`). sing-box keeps
  the TUN and forwards to Xray over a loopback hop (`LocalLink` in `XrayConfigBuilder.kt`).
- **Security rule: never open an unauthenticated local proxy.** Any app on the device can
  connect to 127.0.0.1, learn the server's IP or use the tunnel, even apps excluded from the VPN
  (this is the known Hiddify, v2rayNG, NekoBox and Happ leak).
  - The Xray hop is Shadowsocks 2022 (`2022-blake3-aes-128-gcm`) with a random key per start.
    It is not SOCKS: Xray doesn't authenticate SOCKS UDP at all.
  - No Clash API or Xray API is enabled.
  - The libbox command server is a unix socket in the private files dir.
- Both are built into one AAR by gomobile: `app/libs/libbox.aar`, Java package `io.nekohasekai`
  (`io.nekohasekai.libbox.*`, `io.nekohasekai.xraybridge.Xraybridge`).

### Go (`core/`)

- `core/build.sh` builds the AAR (gomobile bind of `libbox` and `./xraybridge`). Build tags:
  `with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,badlinkname,tfogo_checklinkname0`.
  The sing-box version goes in via `-X github.com/sagernet/sing-box/constant.Version=...`.
  Targets default to `android/arm64,android/amd64` (`TARGETS` overrides).
- `core/go.mod` replaces sing-box and xray-core with `../third_party/{sing-box,xray-core}`;
  gomobile is `github.com/sagernet/gomobile v0.1.13`.
- `core/xraybridge/xraybridge.go`: `Start`, `Stop`, `IsRunning`, `Version`, `SetProtector`.
  Sockets are protected through `internet.RegisterDialerController` → `VpnService.protect`.
- `core/xraybridge/urltest.go`: `URLTest` for latency. It runs a temporary sing-box instance
  (plus a temporary Xray instance for XHTTP nodes) and sends an HTTPS HEAD request through each
  outbound. It returns JSON mapping the tag to the delay in ms, or to an error string.
  - Sockets go through the same Protector. For that, the test config needs
    `route.auto_detect_interface: true`.
  - The service sets the Protector on start and clears it on shutdown.
  - Host test: `ORBIT_TEST_SS=127.0.0.1:18388 go test -tags "$TAGS" -ldflags=-checklinkname=0 ./xraybridge`.
- `third_party/out/` holds host CLIs `sing-box` and `xray` for validating generated configs
  (`sing-box check -c`, `xray run -test -c`). `third_party/sfa` is the upstream SFA app,
  kept for reference only.

### Kotlin (`app/src/main/java/app/orbit/vpn/`)

- `model/`
  - `ProxyNode`: node with `transport` and `subscriptionId`.
  - `Subscription` and `SubscriptionUsage`: parse the `subscription-userinfo` header.
  - `RuleConfig.kt`: `Policy`, `RoutingRule`, `RuleConfig` and `RuleStore`. `RuleStore` holds
    the active config id and the user configs; the built-in `RuleConfig.DEFAULT` (id `default`)
    is implicit, never stored, and read-only.
- `parser/`
  - `LinkParser`: ss/vmess/vless/trojan/hy2/tuic/socks/http links, base64 subscriptions, xhttp.
  - `LinkWriter`: regenerates share links after node edits. Preserve round-trip semantics,
    including explicit empty SNI/ALPN, IPv6, escaped credentials and XHTTP extra JSON.
  - `ShadowrocketConfigParser`: converts `[General] skip-proxy` and `[Rule]`, downloads and
    inlines RULE-SET/DOMAIN-SET, expands `GEOIP,LAN`.
- `config/`
  - `ConfigBuilder`: sing-box JSON. Routing modes: DIRECT, GLOBAL (user rules only), RULE
    (user rules, then the active config's rules).
  - `XrayConfigBuilder`: Xray JSON.
  - `buildUrlTest` in both builders produces the latency-test configs. Outbounds are tagged by
    node id. Server names go through a `hosts` DNS server filled with addresses resolved over
    the physical network.
- `data/`
  - `NodeRepository`: `state.json` holds nodes, settings, subscriptions and user rules;
    `rule-configs.json` holds the `RuleStore`. The legacy single-config `rules.json` is
    migrated on load. Saved nodes are re-parsed from their links at startup.
  - `SubscriptionUpdater`: subscriptions, rule-config import and GeoIP `.srs` download into
    `files/box/`, plus the WorkManager worker (every 12 h).
  - `Updates`: app updates from GitHub Releases of `nettlecode/Orbit-VPN-Client` (`Updates.REPO`).
    It picks `Orbit-<version>-<abi>.apk` for `Build.SUPPORTED_ABIS` (else `-universal`), checks it
    against the release's `SHA256SUMS` and opens the system installer through a `FileProvider`
    (`cache/updates/`). The installer also enforces the same signing key. Release builds check
    once a day when the app opens; debug builds only on request. The asset names come from
    `release.yml`, so keep them in sync.
  - `TrafficHistory`: hourly buckets for 35 days in `traffic.json`.
  - `Backup`: versioned JSON (`app: "orbit"`, `version`) with `AppState` and `RuleStore`, written
    and read through SAF from Settings. Restore replaces everything after a confirmation. When the
    stored data changes shape, bump `version` and keep decoding older files.
  - Encryption is optional: with a password the file is an `Encrypted` envelope (AES-256-GCM).
    The key comes from PBKDF2-HMAC-SHA256 with 600k iterations, about 1 s on a device. The KDF
    and its parameters are stored in the file.
  - PBKDF2 is implemented on top of HmacSHA256 because `PBKDF2WithHmacSHA256` needs API 26 and
    minSdk is 24. A unit test checks it against the JVM implementation.
- `service/`
  - `OrbitVpnService`: `VpnService`, libbox `PlatformInterface` and `CommandServerHandler`.
    Supports `ACTION_STOP`, `ACTION_RELOAD` and `ACTION_READ_SYSTEM_VPN`. Any other intent,
    including the system's `android.net.VpnService` start for always-on VPN, starts the tunnel.
  - `ServiceState`: status, traffic and speed samples, auto-select status, always-on settings.
  - `AutoConnect`: a "running" flag in shared prefs, set on a successful start and cleared on a
    deliberate stop or a failed start. `AutoConnectReceiver` (`BOOT_COMPLETED`,
    `MY_PACKAGE_REPLACED`) restarts the tunnel when the flag is set and
    `AppState.restoreConnection` is on, so reinstalling over USB keeps the VPN up.
  - Always-on VPN and "Block connections without VPN" (kill switch) are system settings: the app
    only links to `Settings.ACTION_VPN_SETTINGS`. Their state is readable only by the running
    service (`isAlwaysOn`, `isLockdownEnabled`, API 29+), which re-reads it when Settings resumes.
    Under lockdown the app's own sockets still work, so reconnecting and latency tests do too.
  - `ConnectionsMonitor`: `CommandConnections`, connected throughout the VPN session even when
    the UI is closed. `connections` contains active connections; `observed` also includes closed
    ones retained by libbox for five minutes. Both are cleared on service stop or restart.
    Snapshots include the source address, outbound chain and matched rule; diagnostics use
    `observed` to identify short-lived tests.
- `ui/ConnectionsScreen.kt`: Active / Journal filters, with the core's matched rule on each card.
  An empty rule means the default route. Tapping opens the full rule, source, destination,
  outbound chain and start time; long-pressing an active connection offers to close it.
- `ui/DomainDiagnostics.kt` and `DomainDiagnosticsScreen.kt`: domain diagnostics opened from
  Settings. `MainViewModel.diagnoseDomain` runs through `launchBusy`. Accepts a hostname only
  (including IDN), resolves it with system DNS, then tests the first address on port 443 with
  TCP, verified TLS (original hostname for SNI/certificate checks) and an HTTPS HEAD request.
  Redirects are not followed; any valid HTTP status is reported, including 4xx/5xx.
  Tests use the current network without socket protection, so Orbit's routing applies when
  connected. Rules for other apps may differ. The actual outbound chain and matched rule come
  from `ConnectionsMonitor.observed`, matched by TCP source port and a new connection id;
  missing observations are shown as unavailable, never inferred as DIRECT.
  Results can open `RuleEditorScreen` with a prefilled domain to create a user rule.
- `service/CoreLogs.kt` and `ui/CoreLogsScreen.kt`: in-memory sing-box log tail from the service's
  `CommandLog` subscription, bounded to 1000 entries / 200k characters. Settings opens the viewer
  with search, copying filtered results and local clear, newest entries first. Each entry is
  capped at 8192 characters. Logs remain after VPN stop, but a core log reset (including a new
  subscription) clears the buffer; process exit loses it too. Clear in the viewer only clears
  the app buffer, not the core buffer or `files/box/box.log`. Xray logs are not included.
- `ui/NodeCreateScreen.kt`: Profiles → Add profile → Enter manually. Choose a protocol,
  then complete the shared node editor with protocol defaults. Nothing is stored until Save;
  `MainViewModel.addNode` adds a local node with a generated share link and reloads the VPN.
- `ui/NodeEditorScreen.kt`: opened from a node's long-press menu in Profiles. Protocol-specific
  credentials, transport and TLS/Reality fields, validation and discard confirmation. Protocol
  type stays fixed. `MainViewModel.saveNode` updates local nodes in place (same id), clears their
  latency and reloads the VPN; subscription nodes are saved as new local copies instead.
  Every edit regenerates `link` via `LinkWriter`, since repository startup re-parses saved links.
- `ui/Latency.kt`: `LatencyTester` runs batches of 16 nodes, 4 batches in parallel. A config
  error in one node fails the whole instance, so a failed batch is split in halves. Errors are
  logged with the `LatencyTester` tag.
- `ui/`: Compose Material3. Five tabs: Home, Profiles, Rules, Traffic, Settings. Routing Mode
  Connections, Domain Diagnostics and Core Logs open as overlays. `MainViewModel` is the single view model.
- `widget/`: Glance home-screen widget (`OrbitWidget`) and Quick Settings tile
  (`VpnTileService`). `OrbitApp.observeForWidgets()` refreshes both. The picker preview is
  `layout/widget_preview.xml` (API 31+, keep it in sync with `OrbitWidget`) and
  `drawable-xxhdpi/widget_preview.png` for older versions (a screenshot of that layout).

## Build, test and run

```sh
./gradlew :app:assembleDebug            # APKs per ABI in app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest        # unit tests; generated configs land in app/build/test-configs/
(cd core && ./build.sh)                 # rebuild libbox.aar after any Go change
```

- Toolchain: AGP 9.3.1 and Kotlin 2.4.10 with `builtInKotlin=false` and `newDsl=false` in
  `gradle.properties`. compileSdk 37 (minor 1), targetSdk 36, minSdk 24. ABI splits are on,
  so there is no `app-debug.apk`: install `app-arm64-v8a-debug.apk` (arm64 devices) or
  `app-universal-debug.apk`.
- Debug builds are `app.orbit.vpn.debug` ("Orbit Debug") and install next to release builds
  (`app.orbit.vpn`). Use `run-as app.orbit.vpn.debug` for debug data.
- Release:
  - `./gradlew :app:assembleRelease` gives signed APKs with R8 (arm64 about 27 MB).
  - Signing reads `keystore.properties` in the root (git-ignored). The key is
    `~/.android-keys/orbit-release.jks`, alias `orbit`. Losing it means updates can't be
    installed over existing releases.
  - `-PorbitVersion=1.2.3` sets versionName and versionCode = 1_02_03.
  - CI: `.github/workflows/release.yml` runs on `v*` tags. It clones the pinned core tags, builds
    the AAR, tests and signs from secrets, then attaches the APKs to a GitHub Release. When
    bumping sing-box or Xray, update the versions there too.
  - Every JNI-facing class must survive R8: keep rules for `go.**`, `io.nekohasekai.libbox.**` and
    `io.nekohasekai.xraybridge.**` are in `app/proguard-rules.pro`. Test release builds on a
    device after adding gomobile-exported APIs.
- adb is not on PATH: use `~/Library/Android/sdk/platform-tools/adb`.
- Devices:
  - On a device that runs a signed release with real data, try changes with a locally built
    *release* APK: it updates in place because the key is the same. A debug build installs as a
    separate, empty "Orbit Debug" app. A reinstall stops the VPN.
  - Emulator `orbit_test` (API 36, arm64), started headless with
    `emulator -avd orbit_test -no-window -no-audio`. A test Shadowsocks server can run on the
    host: port 18388, `aes-256-gcm`, password `orbit-test-pw`, reachable from the emulator as
    `10.0.2.2`.
  - UI automation works through `uiautomator dump`, then `input tap` on node bounds. Watch for
    the on-screen keyboard covering buttons: press back first.
  - README screenshots (`docs/screenshots/`, 540×1200) come from the emulator with demo data
    pushed into the debug app's `files/` via `run-as`. Build without `src/debug/res` so the title
    says "Orbit", use SystemUI demo mode for a clean status bar, and back up and restore the
    emulator's own data around it.

## Hard-won lessons

- DNS server types in sing-box 1.12+ are `https/tls/quic/h3/udp/tcp/local/hosts` (`h3`, not
  `http3`).
- Auto-select (`AppState.autoSelect`, pool `AppState.autoNodes` = favourites, or all servers when
  there are none) is a sing-box `urltest` group tagged `proxy` over one outbound per node, tagged
  with the node id (`ConfigBuilder.buildAuto`). XHTTP members share one Xray instance with an
  inbound per node (`XrayConfigBuilder.buildMany`).
  - One invalid member fails the whole start, so `OrbitVpnService.prepareAuto` runs
    `Libbox.checkConfig` and, on failure, checks each node alone and leaves out the rejected ones.
  - The service reads the group's choice and delays through `CommandGroup` into
    `ServiceState.auto`; the UI shows them and merges the delays into the latency map.
  - sing-box drops a server's delay when a connection through it fails but keeps using it until
    the next scheduled check (`AUTO_INTERVAL`, 5 min). So when the current server has no delay,
    the service asks for a re-measure (`CommandClient.urlTest`, at most every 15 s), which
    switches within seconds.
- ASN rules (`RoutingRule.asns`, `IP-ASN` in Shadowrocket configs, `AS62041` in the editor's IP
  type) use per-AS rule-sets from MetaCubeX/meta-rules-dat (`sing` branch, `asn/AS{asn}.srs`). The
  source is a user setting (`AppState.asnUrl`).
  - ASNs are emitted as a separate route rule with the same outbound, so they are OR'ed with the
    rule's CIDRs and domains.
  - A remote rule-set that is neither local nor downloadable makes sing-box fail to start. So
    `SubscriptionUpdater.downloadRuleSets` prefetches lists on import, rule save and restore, and
    drops AS numbers that return 404.
  - sing-box refetches a cached rule-set by itself when its URL changes (it keeps a URL hash).
- The GeoIP source is a user setting (`AppState.geoipUrl`, a URL template with `{country}`, with
  GitHub and jsDelivr presets), and so is the `update_interval` (`geoipUpdateHours`). Use
  `ConfigBuilder.geoipUrl(country, template)` everywhere, never a hard-coded URL.
- Remote rule-sets need a local `initial_path` and `http_client {detour: proxy}`. Otherwise
  the first start can fail when GitHub is unreachable. GeoIP lists are pre-downloaded at
  import; `assets/geoip-ru.srs` is bundled.
- Default DNS strategy is `ipv4_only`. The TUN still captures IPv6, otherwise sites broke on
  servers without IPv6.
- Do not exclude the app itself from the VPN: with the `mixed` stack that broke TCP. Sockets
  that must bypass the tunnel are protected instead, or bound with `Network.bindSocket`.
- Xray resolves the server address beforehand over the physical network (no lookups through
  the tunnel); the host name is kept for SNI.
- Hijacked DNS queries don't show up in the connection list, so the app shows "connections
  this session" rather than a DNS counter.
- The Glance widget must read state with `collectAsState` to recompose in a running session.
- The emulator's NAT (slirp) sometimes hangs for app sockets: timeouts appear even with the VPN
  off while `adb shell nc` still connects. Reboot the emulator (`adb reboot`).
- On this Mac the `xray` CLI can't reach the internet itself (its `freedom` outbound hangs).
  For host tests, chain Xray's outbound to a `sing-box` mixed/direct instance.
- A Mac with Kaspersky intercepting TLS caused `x509 unknown authority` (now disabled).

## Conventions

- Match the surrounding code: concise KDoc on non-obvious things, no redundant comments.
- Every routing or settings change calls `OrbitVpnService.reload(context)`.
- Network work in the view model goes through `launchBusy`, which reports errors as a snackbar.
