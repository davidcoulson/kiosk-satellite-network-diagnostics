# Network Diagnostics for Kiosk Satellite

WiFi signal, network outage history, and layer-3 latency/loss probing. Most of this plugin needs **no root at all** — only the WiFi signal reading does.

## Requirements

- Kiosk Satellite with **plugin SDK 1 support**.
- Root (e.g. Magisk) for WiFi SSID/RSSI only. Outage tracking and ping probing work without root, and without it enabled at all.

## Install and use

1. Wait for a stable GitHub release and its GitHub Actions build to complete.
2. Open **Plugin Manager > Add plugin**, paste this repository URL, review the manifest and README and choose **Trust and install**.
3. Enable **Network Diagnostics** on its entry row and open the subpage.
4. Optionally set a **Ping target** (your Home Assistant server, router, or any reliable host) to start latency/loss probing. Everything else works with no configuration.

The plugin declares two actions — **Ping now** (an immediate probe outside the regular interval) and **Check root access and capabilities**.

## What it reports

| Signal | Mechanism | Root? |
| --- | --- | --- |
| Network up/down | Subscribes to Kiosk Satellite's own `device.network` event | No |
| Outage history | Counts recovered outages in the last 24h from that same event, with a 10-second merge window so a flapping connection isn't counted as dozens of separate outages | No |
| WiFi SSID + signal (RSSI) | `dumpsys wifi`, parsed for the connected network | Yes |
| Latency + packet loss | An unprivileged ICMP echo (ping) socket to your configured target | No |

## Why the ping probe needs no root

Linux grants unprivileged ICMP datagram sockets to any GID inside `net.ipv4.ping_group_range`, which Android sets wide open — this is the exact mechanism Android's own `ping` command binary uses, and that binary isn't setuid. The plugin opens the same kind of socket directly (`android.system.Os.socket(...)`), sends echo requests, and matches replies by sequence number and a random per-burst token rather than the identifier — the kernel rewrites the identifier to the socket's own port, which is the detail that silently breaks a naive port of textbook raw-socket ping code. An OEM SELinux policy can still deny this to a normal app; when that happens the plugin reports it plainly rather than presenting a permission denial as packet loss.

## Why WiFi signal is different

Reading the currently-connected SSID and signal strength normally goes through `ConnectivityManager`/`WifiManager`, which need a live Android `Context` — a value this plugin has no way to obtain (`KioskPlugin.start` hands it a `PluginHost`, not a `Context`, and the SDK exposes no equivalent). `dumpsys wifi` is the fallback: reachable only as root (a plain app calling `dumpsys` is refused with a permission denial), but it needs no cooperation from Kiosk Satellite itself and works the same way regardless of Android version or OEM `dumpsys` formatting quirks the regex parsing tolerates.

## Outage tracking is simplified from the reference implementation

This plugin's merge-window (10 seconds) and attention threshold (6+ outages in 24h flags the link as needing attention) constants come from ha-paneld's own `WifiOutageTracker`, calibrated against real panel hardware. What's deliberately **not** ported is that implementation's cross-restart persistence and clock-correction handling — this plugin has no `Context` and therefore nowhere durable to keep state, so outage history resets each time the plugin (re)starts, same as every other piece of session-scoped state in this plugin family. A firmware or app restart naturally clearing the count is an acceptable trade for the added complexity persistence would need here.

## Build and test

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=/path/to/android-sdk
python3 tools/test.py
python3 tools/build.py
```

`tools/test.py` compiles the whole source tree against `android.jar` (needed at compile time for the `android.system.Os` ICMP socket calls, even though the test itself never invokes them) and runs device-free logic tests: SSID/RSSI normalization, `dumpsys wifi` parsing (verified against a real 14,000-line capture from a physical panel, not just synthetic fixtures), the outage merge-window boundary, and the ICMP wire format's request/reply matching. `tools/build.py` produces the ZIP, checksum and manifest in `dist/`.

## Publishing and handoff

Apache-2.0. The plugin ID is `network-diagnostics`. See [jxlarrea/kiosk-satellite-plugin-hello-world](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world) for the SDK 1 documentation this plugin was built against.
