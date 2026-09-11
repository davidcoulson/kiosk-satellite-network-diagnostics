// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

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
    private static final Pattern WIFI_INFO_SSID =
        Pattern.compile("(?im)\\bSSID:\\s*(.*?),\\s*BSSID:");
    private static final Pattern RSSI = Pattern.compile("(?im)\\bRSSI\\s*[:=]\\s*(-?\\d+)");

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

    static final class WifiSnapshot {
        final String ssid;
        final Integer rssiDbm;
        WifiSnapshot(String ssid, Integer rssiDbm) {
            this.ssid = ssid;
            this.rssiDbm = rssiDbm;
        }
    }

    /** Parses `dumpsys wifi` output for the currently connected SSID and
     *  RSSI. Both fields null when nothing matches (not connected, or an
     *  unrecognized dumpsys format on this Android version/OEM). */
    static WifiSnapshot parseDumpsysWifi(String raw) {
        if (raw == null || raw.isEmpty()) return new WifiSnapshot(null, null);
        String connectedSsid = firstGroup(CONNECTED_SSID, raw);
        String wifiInfoSsid = firstGroup(WIFI_INFO_SSID, raw);
        String rssiRaw = firstGroup(RSSI, raw);
        Integer rssi = null;
        if (rssiRaw != null) {
            try {
                rssi = normalizedRssi(Integer.parseInt(rssiRaw));
            } catch (NumberFormatException ignored) {}
        }
        return new WifiSnapshot(normalizedSsid(connectedSsid != null ? connectedSsid : wifiInfoSsid), rssi);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
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
}
