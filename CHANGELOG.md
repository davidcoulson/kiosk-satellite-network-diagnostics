# Changelog

## 0.1.0

- Network up/down and 24h outage history via Kiosk Satellite's own `device.network` event, with a 10-second merge window so a flapping connection isn't over-counted.
- WiFi SSID and RSSI via `dumpsys wifi` (root).
- Latency and packet loss to a configured target via an unprivileged ICMP echo socket — no root required, the same mechanism Android's own `ping` binary uses.
- Simulation mode for testing without root, network, or a real target.
