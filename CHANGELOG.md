# Changelog

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
