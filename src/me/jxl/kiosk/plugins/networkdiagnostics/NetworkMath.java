// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure, device-free parsing — no process launches, no Android APIs. Kept
 * separate from {@link NetworkDiagnosticsPlugin} so it's unit-testable;
 * see test/NetworkMathTest.java.
 *
 * WiFi regex patterns ported from ha-paneld's WifiDiagnostics.kt
 * (`normalizedWifiSsid`/`normalizedWifiRssi`/`parseWifiShellSnapshot`),
 * which parses `dumpsys wifi` text — the root-shell fallback path that
 * plugin uses when its Context-based direct API can't answer. This
 * plugin has no Context at all, so `dumpsys wifi` is the only path.
 */
final class NetworkMath {
    private NetworkMath() {}

    private static final Pattern CONNECTED_SSID =
        Pattern.compile("(?im)Wi-?Fi\\s+is\\s+connected\\s+to\\s+\"([^\"]+)\"");
    // Real captured format (`mWifiInfo SSID: X, BSSID: Y, MAC: ..., RSSI: N, ...`) keeps
    // SSID/BSSID/RSSI on one line, so a single combined pattern — SSID up to BSSID, BSSID
    // up to the next field, then a lazy scan to RSSI on the same line (`.` doesn't cross
    // \n without DOTALL) — captures all three atomically and can't cross-reference an
    // unrelated saved-network line elsewhere in the (often huge) dump.
    private static final Pattern WIFI_INFO_FULL = Pattern.compile(
        "(?im)\\bSSID:\\s*(.*?),\\s*BSSID:\\s*([0-9A-Fa-f:]+|<[^>]*>|null).*?\\bRSSI\\s*[:=]\\s*(-?\\d+)");
    // Degraded fallback for an OEM/API-level format this doesn't match: SSID and RSSI
    // separately, no BSSID.
    private static final Pattern WIFI_INFO_SSID =
        Pattern.compile("(?im)\\bSSID:\\s*(.*?),\\s*BSSID:");
    private static final Pattern RSSI = Pattern.compile("(?im)\\bRSSI\\s*[:=]\\s*(-?\\d+)");
    private static final Pattern BSSID_STANDALONE =
        Pattern.compile("(?i)^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$");

    /** Strips surrounding quotes and rejects Android's placeholder values
     *  for "no SSID known" (`<unknown ssid>`, seen in different casings
     *  across API levels). Null means "not connected" or "not parseable",
     *  never an empty string. */
    static String normalizedSsid(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
            value = value.substring(1, value.length() - 1).trim();
        }
        if (value.isEmpty()) return null;
        if (value.equalsIgnoreCase("<unknown ssid>") || value.equalsIgnoreCase("unknown ssid")) return null;
        return value;
    }

    /** RSSI is dBm and must be zero or negative; anything positive (or a
     *  sentinel like Integer.MAX_VALUE for "unknown") is not a real
     *  reading. -126 is comfortably below any real WiFi radio's floor. */
    static Integer normalizedRssi(int raw) {
        return (raw <= 0 && raw >= -126) ? raw : null;
    }

    /** A real-looking BSSID (a MAC address), or null for a placeholder
     *  (`null`, `<removed>`-style) or unparseable value. */
    static String normalizedBssid(String raw) {
        if (raw == null) return null;
        String value = raw.trim();
        return BSSID_STANDALONE.matcher(value).matches() ? value.toLowerCase(java.util.Locale.ROOT) : null;
    }

    static final class WifiSnapshot {
        final String ssid;
        final String bssid;
        final Integer rssiDbm;
        WifiSnapshot(String ssid, String bssid, Integer rssiDbm) {
            this.ssid = ssid;
            this.bssid = bssid;
            this.rssiDbm = rssiDbm;
        }
    }

    /** Parses `dumpsys wifi` output for the currently connected SSID,
     *  BSSID and RSSI. All fields null when nothing matches (not
     *  connected, or an unrecognized dumpsys format on this Android
     *  version/OEM). */
    static WifiSnapshot parseDumpsysWifi(String raw) {
        if (raw == null || raw.isEmpty()) return new WifiSnapshot(null, null, null);
        Matcher full = WIFI_INFO_FULL.matcher(raw);
        if (full.find()) {
            Integer rssi = parseRssi(full.group(3));
            return new WifiSnapshot(normalizedSsid(full.group(1)), normalizedBssid(full.group(2)), rssi);
        }
        String connectedSsid = firstGroup(CONNECTED_SSID, raw);
        String wifiInfoSsid = firstGroup(WIFI_INFO_SSID, raw);
        Integer rssi = parseRssi(firstGroup(RSSI, raw));
        return new WifiSnapshot(normalizedSsid(connectedSsid != null ? connectedSsid : wifiInfoSsid), null, rssi);
    }

    private static Integer parseRssi(String rawRssi) {
        if (rawRssi == null) return null;
        try {
            return normalizedRssi(Integer.parseInt(rawRssi));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    // --- `ip route get` parsing — root-free (confirmed on real hardware: a plain,
    // unprivileged shell can run `ip route get <probe>` and read the default route's
    // gateway and outbound interface). This is what determines wired-vs-WiFi and the
    // gateway to ping; only the detailed WiFi SSID/BSSID/RSSI above needs root.

    static final class RouteInfo {
        final String gatewayIp; // null for a direct route (no gateway) or unparseable output
        final String iface;     // null only when even the interface can't be found
        final String localIp;   // this panel's own source address for the route, or null
        RouteInfo(String gatewayIp, String iface, String localIp) {
            this.gatewayIp = gatewayIp;
            this.iface = iface;
            this.localIp = localIp;
        }
    }

    private static final Pattern ROUTE_VIA_DEV = Pattern.compile("\\bvia\\s+(\\S+)\\s+dev\\s+(\\S+)");
    private static final Pattern ROUTE_DEV_ONLY = Pattern.compile("\\bdev\\s+(\\S+)");
    private static final Pattern ROUTE_SRC = Pattern.compile("\\bsrc\\s+(\\S+)");

    /** Parses `ip route get <probe>` output — real captured shape:
     *  `8.8.8.8 via 10.2.4.1 dev eth0 table 1009 src 10.2.4.129 uid 2000`
     *  (a direct/same-subnet route omits the `via` gateway clause; `src`
     *  is this panel's own address the kernel would send from). */
    static RouteInfo parseIpRouteGet(String raw) {
        if (raw == null || raw.isEmpty()) return new RouteInfo(null, null, null);
        String localIp = firstGroup(ROUTE_SRC, raw);
        Matcher withGateway = ROUTE_VIA_DEV.matcher(raw);
        if (withGateway.find()) return new RouteInfo(withGateway.group(1), withGateway.group(2), localIp);
        Matcher devOnly = ROUTE_DEV_ONLY.matcher(raw);
        return new RouteInfo(null, devOnly.find() ? devOnly.group(1) : null, localIp);
    }

    /** "wifi", "ethernet", or "other:<name>" for anything else (a VPN
     *  tunnel, USB tethering, etc.) — reported honestly rather than
     *  guessed at. Null iface (nothing parsed at all) stays null. */
    static String classifyInterface(String iface) {
        if (iface == null) return null;
        if (iface.startsWith("wlan")) return "wifi";
        if (iface.startsWith("eth")) return "ethernet";
        return "other:" + iface;
    }

    // --- Outage episode merging — simplified from ha-paneld's WifiOutageTracker: same
    // merge-window and attention-threshold constants (calibrated against real panel
    // hardware — see the source), no cross-restart persistence (this plugin has no
    // Context and therefore nowhere durable to keep one; resets each plugin start,
    // same convention as every other in-session-only tracker this session's plugins use).

    /** A re-loss within this many ms of the last counted recovery is the
     *  same disturbance continuing, not a second episode. */
    static final long MERGE_WINDOW_MS = 10_000L;
    /** From ha-paneld's own calibration: eleven episodes in 24h on a
     *  measured bad day; six is roughly half of that, so a single blip
     *  never trips it but a truly unstable link does, with margin. */
    static final int ATTENTION_24H = 6;
    static final long WINDOW_MS = 24L * 3_600_000L;

    /** Whether a network loss recovering [msSinceLastRecovery] after the
     *  last COUNTED recovery is the same disturbance continuing (true =
     *  already counted, don't open a new episode) — null
     *  [msSinceLastRecovery] means there is no prior recovery to compare
     *  against, so it can never be a merge. */
    static boolean isMergedRecovery(Long msSinceLastRecovery) {
        return msSinceLastRecovery != null && msSinceLastRecovery >= 0 && msSinceLastRecovery <= MERGE_WINDOW_MS;
    }

    // --- Latency percentile — ha-paneld's own runtime diagnostics reports round-trip
    // latency as a p95 over a rolling window ("healthy; p95 5 ms, no misses in the last
    // 5 min"), not just the latest sample: a single burst's RTT is noisy, but p95 over
    // recent history is a stable enough number to alert on.

    /**
     * The [p]th percentile (0..100) of [samples], nearest-rank method — the
     * smallest value at or above which at least [p]% of samples fall.
     * [samples] need not be sorted; this copies and sorts rather than
     * mutating the caller's list. Empty input returns -1 (no data, not a
     * bogus 0). [p] is clamped to 0..100 defensively.
     */
    static double percentile(List<Double> samples, double p) {
        if (samples.isEmpty()) return -1.0;
        List<Double> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        double clamped = Math.max(0.0, Math.min(100.0, p));
        int rank = (int) Math.ceil(clamped / 100.0 * sorted.size());
        int index = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(index);
    }

    /**
     * Builds the {@code publishSeries} payload for one target's retained
     * RTT history — a single-series compact line chart. Null (not
     * published) with fewer than two samples: the host requires strictly
     * increasing timestamps, and a one-point chart isn't useful anyway.
     * Gateway and target each get their own chart (rather than one shared
     * chart with two series) since their histories can have different
     * lengths and timestamps — a configured ping target started later
     * than the gateway, for instance — and a chart's series must all share
     * one timestamps array.
     */
    static Map<String, Object> latencyChart(String title, List<Long> timestampsMs, List<Double> rttMs) {
        if (timestampsMs.size() < 2) return null;
        Map<String, Object> series = new HashMap<>();
        series.put("name", title);
        series.put("values", new ArrayList<>(rttMs));
        Map<String, Object> chart = new HashMap<>();
        chart.put("title", title);
        chart.put("unit", "ms");
        chart.put("type", "line");
        chart.put("compact", true);
        chart.put("timestamps", new ArrayList<>(timestampsMs));
        chart.put("series", Collections.singletonList(series));
        return chart;
    }
}
