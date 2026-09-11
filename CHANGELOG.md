# Changelog

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
