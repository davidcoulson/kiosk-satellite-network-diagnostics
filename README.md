# Network Diagnostics for Kiosk Satellite

Connection type, WiFi signal, network outage history, and three latency readings: your configured target, the default gateway, and a dashboard URL's HTTP response time. Most of this plugin needs **no root at all** — only the detailed WiFi SSID/BSSID/RSSI reading does.

Every applicable reading is published both as plugin-subpage status text and as a real Home Assistant sensor/text-sensor/binary-sensor entity — SDK 1 added entity types beyond RGB lights in jxlarrea/kiosk-satellite's "Add SDK 1 plugin sensors, selects and bar charts", resolving [the upstream feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/2) this plugin used to be blocked on. See "Home Assistant entities" below for exactly what's exposed and when.

## Requirements

- Kiosk Satellite with **plugin SDK 1 support**.
- Root (e.g. Magisk) for WiFi SSID/BSSID/RSSI only. Everything else — connection type, outage tracking, gateway ping, target ping, dashboard latency — works without root.

## Install and use

1. Wait for a stable GitHub release and its GitHub Actions build to complete.
2. Open **Plugin Manager > Add plugin**, paste this repository URL, review the manifest and README and choose **Trust and install**.
3. Enable **Network Diagnostics** on its entry row and open the subpage.
4. Connection type, WiFi detail (if applicable) and gateway ping work immediately, no configuration needed. Optionally set a **Ping target** and/or **Dashboard URL** for the other two latency readings.

The plugin declares two actions — **Ping now** (an immediate probe cycle outside the regular interval) and **Check root access and capabilities**.

## What it reports

| Signal | Mechanism | Root? |
| --- | --- | --- |
| Connection type (WiFi/Ethernet/other) | `ip route get`, classified by the default route's interface name | No |
| Local IP address | The same `ip route get` call's `src` field | No |
| Network up/down | Subscribes to Kiosk Satellite's own `device.network` event | No |
| Outage history | Counts recovered outages in the last 24h from that same event, with a 10-second merge window so a flapping connection isn't counted as dozens of separate outages | No |
| WiFi SSID, BSSID, signal (RSSI) | `dumpsys wifi`, parsed for the connected network | Yes |
| Gateway latency + loss, rolling p95, 5-min miss count | An unprivileged ICMP echo (ping) socket to the default gateway found via `ip route get` | No |
| Target latency + loss, rolling p95, 5-min miss count | The same ICMP mechanism, against your configured **Ping target** | No |
| Dashboard responsiveness | A plain HTTP GET to your configured **Dashboard URL**, timed to first response headers | No |

## Home Assistant entities

Every reading above publishes as an entity the moment it's applicable, and is removed the moment it stops being applicable (e.g. clearing the **Ping target** setting removes the target's latency/loss/p95 entities rather than leaving them stuck on a stale value):

| Entity | Type | When it exists |
| --- | --- | --- |
| Network up | binary_sensor (`connectivity`) | Always |
| Connection type | text_sensor | Always |
| WiFi signal | sensor (dBm) | On WiFi, rooted |
| WiFi SSID | sensor (text) | On WiFi, rooted |
| Gateway latency, packet loss | sensor (ms, %) | A gateway is known and at least one recent echo came back |
| Gateway p95 latency | sensor (ms) | Once the gateway has rolling history |
| Ping target latency, packet loss | sensor (ms, %) | **Ping target** is set and at least one recent echo came back |
| Ping target p95 latency | sensor (ms) | Once the target has rolling history |
| Dashboard response time | sensor (ms) | **Dashboard URL** is set and the last probe succeeded |
| Outages (24h) | sensor (count) | Always |

Up to 12 entities, well under SDK 1's 32-entity-per-plugin cap. Values update on the same cadence as status text (the probe interval, or immediately on a `device.network` transition) — each entity is a snapshot of "right now," not a history; see p95/miss tracking above for the built-in stability signal, and Latency history charts below for an actual graph.

## Latency history charts

Alongside the entities, the gateway and your configured **Ping target** each get their own compact line chart of retained RTT history (up to 60 samples — 30 minutes at the default 30-second probe interval), visible in both the on-device plugin subpage and Remote Admin. Two separate charts, not one shared chart with two series: the gateway and target histories can have different lengths and start times (a ping target configured after the plugin's already been running for a while, for instance), and a chart's series all have to share one timestamps array. Not published until a target has at least two retained samples.

## Why the ping probe needs no root

Linux grants unprivileged ICMP datagram sockets to any GID inside `net.ipv4.ping_group_range`, which Android sets wide open — this is the exact mechanism Android's own `ping` command binary uses, and that binary isn't setuid. The plugin opens the same kind of socket directly (`android.system.Os.socket(...)`), sends echo requests, and matches replies by sequence number and a random per-burst token rather than the identifier — the kernel rewrites the identifier to the socket's own port, which is the detail that silently breaks a naive port of textbook raw-socket ping code. An OEM SELinux policy can still deny this to a normal app; when that happens the plugin reports it plainly rather than presenting a permission denial as packet loss.

## Why WiFi signal is different

Reading the currently-connected SSID and signal strength normally goes through `ConnectivityManager`/`WifiManager`, which need a live Android `Context` — a value this plugin has no way to obtain (`KioskPlugin.start` hands it a `PluginHost`, not a `Context`, and the SDK exposes no equivalent). `dumpsys wifi` is the fallback: reachable only as root (a plain app calling `dumpsys` is refused with a permission denial), but it needs no cooperation from Kiosk Satellite itself and works the same way regardless of Android version or OEM `dumpsys` formatting quirks the regex parsing tolerates. SSID, BSSID and RSSI are all parsed from the same `dumpsys` line atomically, verified against a real 14,000-line capture from a physical panel — pulling them from separate matches risked a BSSID from one saved-network row getting attributed to an unrelated SSID from another.

## Connection type and gateway need no root at all

`ip route get <probe-ip>` — confirmed directly on real hardware, both Ethernet and WiFi panels — runs as a plain, unprivileged shell command and reports both the default route's outbound interface (`wlan0`/`eth0`/anything else, classified honestly rather than guessed at) and its gateway. This is also where the gateway IP for the gateway-ping reading comes from; no root, no `dumpsys`, no `ConnectivityManager`.

## Dashboard URL: a stand-in, not a real page-load timer

Kiosk Satellite's own configured dashboard/Home Assistant URL isn't something this plugin can discover — the SDK's read commands (`getStats`/`getUptime`) don't expose it, so the **Dashboard URL** setting asks you to provide one by hand. Filed as [an upstream feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/4) for a read command exposing the configured URL (and ideally the current view) — deliberately scoped to the URL only, no auth token, since this plugin only ever times plain HTTP response headers and never needs to authenticate or read real dashboard content.

What's measured today is time to receive HTTP response headers on a plain GET, not full page render, JavaScript execution, or a WebSocket handshake completing (see the [chart-rendering SDK gap](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/1), filed while scoping a real WebView-responsiveness feature for a different plugin, for what a genuine dashboard-jank measurement would actually need). It's a rough network+server reachability signal, not a UX metric.

## p95 latency and miss tracking

A single ping burst's round-trip time is noisy — one slow reading doesn't mean much on its own. Each pinged target (the gateway, and your configured **Ping target**) keeps a rolling window of its last 60 bursts and reports the 95th-percentile RTT alongside the latest reading, plus how many of the last 5 minutes' bursts came back with zero replies ("misses"). This mirrors ha-paneld's own runtime-diagnostics framing (`healthy; p95 5 ms, no misses in the last 5 min`) — a stable number to alert on, rather than reacting to every noisy individual sample. In-memory only, same as the outage tracker below: resets on plugin restart.

## Outage tracking is simplified from the reference implementation

This plugin's merge-window (10 seconds) and attention threshold (6+ outages in 24h flags the link as needing attention) constants come from ha-paneld's own `WifiOutageTracker`, calibrated against real panel hardware. What's deliberately **not** ported is that implementation's cross-restart persistence and clock-correction handling — this plugin has no `Context` and therefore nowhere durable to keep state, so outage history resets each time the plugin (re)starts, same as every other piece of session-scoped state in this plugin family. A firmware or app restart naturally clearing the count is an acceptable trade for the added complexity persistence would need here.

## Build and test

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` compiles the whole source tree against `android.jar` (needed at compile time for the `android.system.Os` ICMP socket calls, even though the test itself never invokes them) and runs device-free logic tests: SSID/BSSID/RSSI normalization, `dumpsys wifi` parsing, `ip route get` parsing and interface classification (all verified against real captures from physical panels, not just synthetic fixtures), the outage merge-window boundary, the ICMP wire format's request/reply matching, the latency-chart payload building, and which entities `NetworkEntities.compute` produces (and omits) for a fully-populated, a minimal/unconfigured, and a currently-unreachable reading. `tools/build.py` produces the ZIP, checksum and manifest in `dist/`.

## Publishing and handoff

Apache-2.0. The plugin ID is `network-diagnostics`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.

Author: David Coulson. Built with AI assistance (Claude Code).
