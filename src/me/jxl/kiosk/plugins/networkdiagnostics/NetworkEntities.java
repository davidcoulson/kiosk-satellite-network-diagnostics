// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure mapping from this plugin's latest readings to the set of SDK 1
 * entities it should publish today — no PluginHost calls, so it's
 * unit-testable; see test/NetworkEntitiesTest.java. Parameters are plain
 * primitives/Strings rather than this plugin's own richer types
 * (PathBurst, LatencyTracker, DashboardProbe.Result) so this class needs
 * no reflection gymnastics to test — the plugin unpacks its own state
 * before calling {@link #compute}.
 *
 * The plugin diffs the returned list's {@link Entity#id()}s against what
 * it last actually published, to remove entities for features that just
 * became inapplicable (e.g. a cleared ping target) rather than leaving a
 * permanently-unknown entity behind in Home Assistant.
 */
final class NetworkEntities {
    private NetworkEntities() {}

    static final class Entity {
        final String type; // sensor | text_sensor | binary_sensor
        final String key;
        final String name;
        final Map<String, Object> metadata;
        final Object state; // Double, String, Boolean, or null

        Entity(String type, String key, String name, Map<String, Object> metadata, Object state) {
            this.type = type;
            this.key = key;
            this.name = name;
            this.metadata = metadata;
            this.state = state;
        }

        String id() {
            return type + "." + key;
        }
    }

    static List<Entity> compute(
        Boolean networkUp, String connectionType,
        String wifiSsid, Integer wifiRssiDbm,
        String gatewayIp, Double gatewayAvgRttMs, Double gatewayLossPercent, Double gatewayP95Ms,
        String pingTarget, Double targetAvgRttMs, Double targetLossPercent, Double targetP95Ms,
        String dashboardUrl, boolean dashboardOk, Long dashboardElapsedMs,
        int outages24h
    ) {
        List<Entity> entities = new ArrayList<>();
        entities.add(new Entity("binary_sensor", "network_up", "Network up", deviceClass("connectivity"), networkUp));

        String friendly = friendlyConnectionType(connectionType);
        if (friendly != null) {
            entities.add(new Entity("text_sensor", "connection_type", "Connection type", Collections.emptyMap(), friendly));
        }

        if ("wifi".equals(connectionType)) {
            if (wifiRssiDbm != null) {
                entities.add(new Entity("sensor", "wifi_rssi", "WiFi signal", signalMetadata(), wifiRssiDbm.doubleValue()));
            }
            if (wifiSsid != null) {
                entities.add(new Entity("text_sensor", "wifi_ssid", "WiFi SSID", Collections.emptyMap(), wifiSsid));
            }
        }

        if (gatewayIp != null) {
            if (gatewayAvgRttMs != null) {
                entities.add(new Entity("sensor", "gateway_latency_ms", "Gateway latency", msMetadata(), gatewayAvgRttMs));
                entities.add(new Entity("sensor", "gateway_loss_percent", "Gateway packet loss", percentMetadata(), gatewayLossPercent));
            }
            if (gatewayP95Ms != null) {
                entities.add(new Entity("sensor", "gateway_p95_ms", "Gateway p95 latency", msMetadata(), gatewayP95Ms));
            }
        }

        if (pingTarget != null && !pingTarget.isEmpty()) {
            if (targetAvgRttMs != null) {
                entities.add(new Entity("sensor", "target_latency_ms", "Ping latency", msMetadata(), targetAvgRttMs));
                entities.add(new Entity("sensor", "target_loss_percent", "Ping packet loss", percentMetadata(), targetLossPercent));
            }
            if (targetP95Ms != null) {
                entities.add(new Entity("sensor", "target_p95_ms", "Ping p95 latency", msMetadata(), targetP95Ms));
            }
        }

        if (dashboardUrl != null && !dashboardUrl.isEmpty() && dashboardOk && dashboardElapsedMs != null) {
            entities.add(new Entity("sensor", "dashboard_response_ms", "Dashboard response time", msMetadata(), dashboardElapsedMs.doubleValue()));
        }

        entities.add(new Entity("sensor", "outages_24h", "Outages (24h)", countMetadata(), (double) outages24h));
        return entities;
    }

    /** "wifi" -> "WiFi", "ethernet" -> "Ethernet", "other:usb0" -> "Other (usb0)". Null passes through as null (unknown yet). */
    static String friendlyConnectionType(String raw) {
        if (raw == null) return null;
        if ("wifi".equals(raw)) return "WiFi";
        if ("ethernet".equals(raw)) return "Ethernet";
        if (raw.startsWith("other:")) return "Other (" + raw.substring(6) + ")";
        return raw;
    }

    private static Map<String, Object> deviceClass(String value) {
        Map<String, Object> m = new HashMap<>();
        m.put("deviceClass", value);
        return m;
    }

    private static Map<String, Object> msMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("unit", "ms");
        m.put("stateClass", "measurement");
        return m;
    }

    private static Map<String, Object> percentMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("unit", "%");
        m.put("stateClass", "measurement");
        return m;
    }

    private static Map<String, Object> signalMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("unit", "dBm");
        m.put("deviceClass", "signal_strength");
        m.put("stateClass", "measurement");
        return m;
    }

    private static Map<String, Object> countMetadata() {
        Map<String, Object> m = new HashMap<>();
        m.put("stateClass", "measurement");
        return m;
    }
}
