# Changelog

## 0.8.0

- **Root is no longer required.** Both privileged reads work as Android's shell user, so a panel running Shizuku needs no root at all — verified on real hardware with root deliberately unused. This was the only plugin in the set whose every privileged command clears that bar, which is why it goes first: requiring root excludes most hardware people actually own.
- Root is still preferred when present. The persistent session costs one grant for the plugin's lifetime and a write-and-read per command; every Shizuku call is a fresh binder round trip to a helper process. Shizuku is the fallback, not the replacement.
- The status line names the active channel, and whether Shizuku is running as shell or root — if a reading is missing, that's the first thing worth knowing.
- Shell syntax is unchanged. The host's Shizuku call takes an executable and arguments with no shell interpretation, but `/system/bin/sh -c <script>` is an ordinary executable, so pipes and redirection work the same on both channels. What differs is the permissions commands run with, never the syntax.

## 0.7.0

- **One root shell per plugin instead of one per command.** Every root call used to spawn a fresh `su`, and Magisk shows its "granted Superuser rights" toast per request. The plugin now holds a single `su` session and writes commands to its stdin, so root is granted once per plugin start.
- Commands are framed by a per-session random sentinel (`echo <token>:$?`), so exit codes and output read exactly as before. Each command runs in a subshell, so one containing `exit` ends that subshell rather than silently killing the session and costing root for the rest of the plugin's life.
- A timeout or a dead shell closes the session and the next call opens a clean one. Late output from a timed-out command can't be told apart from the next command's, so resynchronising would be guesswork — it's killed instead. Failures cost one extra grant, never silent corruption.
- The session ends with the plugin: `stop()` closes it, so disabling the plugin doesn't leave a root shell alive.
- Tested against `sh` rather than `su`, which needs no root or device: the load-bearing assertion is that two commands report the same PID, since a regression to per-command spawning would only show up as toast spam on a panel.

## 0.6.0

- Discover the dashboard URL automatically via the new `getDashboardState` read command ("Expose sanitized dashboard state to SDK 1 plugins" upstream, resolving [the feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/4) filed for exactly this). The **Dashboard URL** setting is now optional — leave it empty and the plugin times whatever the panel is actually showing, preferring the live `currentUrl` over the configured Home Assistant URL. The setting remains an override for timing a different URL, and a fallback if discovery returns nothing. Status text labels the discovered case as **Dashboard (auto)**.

## 0.5.0

- Publish a compact latency-history chart for the gateway and, when configured, the ping target — separate charts (not one shared chart with two series), since the two histories can have different lengths and start times. Up to 60 retained RTT samples each (30 minutes at the default 30s probe interval).
- Filed [an upstream feature request](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/4) for a read command exposing Kiosk Satellite's own configured dashboard URL (and current view), so the **Dashboard URL** setting could eventually be auto-populated instead of typed in by hand. Deliberately scoped to the URL only, no auth token — this plugin only times plain HTTP response headers and never needs to authenticate.

## 0.4.0

- Publish every applicable reading as a real Home Assistant entity (binary_sensor, sensor, text_sensor), alongside the existing status text — network up, connection type, WiFi signal/SSID, gateway and target latency/loss/p95, dashboard response time, and 24h outage count. Declares the `entities` capability. Entities appear and disappear as readings become applicable or not (e.g. clearing the ping target removes its entities). Resolves the upstream SDK gap this plugin used to document as a limitation, now shipped in jxlarrea/kiosk-satellite's "Add SDK 1 plugin sensors, selects and bar charts".

## 0.3.1-20260911

- Correction: an earlier attempt at this release used a 4-component date-based version (`2026.09.11.01`), which Kiosk Satellite's plugin manifest validator rejects (`FormatException: Invalid plugin ID or version`) — it requires 3-component semver, optionally with a `-suffix`. That broken release has been removed; this one embeds the date as a semver prerelease suffix instead.
- Metadata only otherwise: author field and AI-assisted note in the README.

## 0.3.0

- Local IP address, from the same root-free `ip route get` call (its `src` field).
- Rolling p95 latency and a 5-minute miss count for both the gateway and configured target ping, alongside the existing latest-burst reading — matches ha-paneld's own "healthy; p95 5 ms, no misses in the last 5 min" runtime-diagnostics framing.

## 0.2.0

- Connection type (WiFi/Ethernet/other), from `ip route get`'s default-route interface — no root.
- BSSID alongside the existing WiFi SSID/RSSI, parsed from the same `dumpsys wifi` line atomically.
- Gateway latency and packet loss — the same root-free ICMP mechanism as the configured ping target, against the gateway `ip route get` finds automatically.
- Dashboard URL HTTP response-time reading (a rough reachability stand-in, not a real page-load timer — Kiosk Satellite's own dashboard URL isn't discoverable through the SDK).
- Everything above reported as status text; real Home Assistant sensor entities are blocked on [jxlarrea/kiosk-satellite-plugin-hello-world#2](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/issues/2) (SDK 1's `entities` capability only supports RGB lights).

## 0.1.0

- Network up/down and 24h outage history via Kiosk Satellite's own `device.network` event, with a 10-second merge window so a flapping connection isn't over-counted.
- WiFi SSID and RSSI via `dumpsys wifi` (root).
- Latency and packet loss to a configured target via an unprivileged ICMP echo socket — no root required, the same mechanism Android's own `ping` binary uses.
- Simulation mode for testing without root, network, or a real target.
