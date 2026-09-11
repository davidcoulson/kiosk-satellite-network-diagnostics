// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Connection type (WiFi/Ethernet, root-free via `ip route get`), WiFi
 * signal (root, `dumpsys wifi`), network outage history (root-free —
 * driven by the host's own {@code device.network} event), and three
 * latency readings: a configured ping target, the default gateway (both
 * root-free — an unprivileged ICMP socket, the same mechanism Android's
 * own `ping` binary uses), and a configured dashboard URL's HTTP response
 * time (root-free — plain {@code HttpURLConnection}).
 *
 * Everything here is status text, not real Home Assistant entities: SDK
 * 1's {@code entities} capability only covers RGB lights (see this
 * plugin's README and the upstream feature request it links) — there is
 * no sensor/text-sensor entity type to publish this into today.
 *
 * Outage tracking is a simplified port of ha-paneld's WifiOutageTracker:
 * same merge-window and attention-threshold constants (see NetworkMath),
 * no cross-restart persistence (this plugin has no Context and nowhere
 * durable to keep one — same convention as every other in-session-only
 * tracker this session's plugins use). Episodes still open when the
 * plugin stops are dropped, never retroactively counted.
 */
public final class NetworkDiagnosticsPlugin implements KioskPlugin {
    private static final int PING_ECHOES = 4;
    private static final long PING_ECHO_TIMEOUT_MS = 1200L;

    private final AtomicBoolean alive = new AtomicBoolean();
    private PluginHost host;
    private ScheduledExecutorService worker;
    private Map<String, Object> settings = new HashMap<>();
    private final IcmpEchoSource icmpSource = new IcmpEchoSource();

    private volatile boolean rooted;
    private Boolean lastSimulation;
    private ScheduledFuture<?> probeTask;

    // Latest observations, for status reporting.
    private volatile Boolean networkUp;
    private volatile String connectionType; // "wifi" | "ethernet" | "other:<iface>" | null
    private volatile String gatewayIp;
    private volatile NetworkMath.WifiSnapshot wifi;
    private volatile PathBurst targetBurst;
    private volatile PathBurst gatewayBurst;
    private volatile DashboardProbe.Result dashboardResult;

    // Outage episode tracking (in-memory only — see class doc).
    private Long openEpisodeStartMs;
    private Long lastRecoveryAtMs;
    private final Deque<Long> episodeStartsMs = new ArrayDeque<>();
    private static final int MAX_RETAINED_EPISODES = 200;

    public void start(PluginHost host, Map<String, Object> settings) {
        this.host = host;
        alive.set(true);
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "network-diagnostics");
            t.setDaemon(true);
            return t;
        });
        worker.execute(() -> {
            try {
                host.subscribe("device.network");
            } catch (Exception ignored) {}
            host.executeCommand("getUptime", Collections.emptyMap(), (ok, data, error) -> submit(() -> {
                if (ok && data instanceof Map) {
                    networkUp = ((Map<?, ?>) data).get("network") != null;
                }
            }));
        });
        configure(settings);
    }

    public void configure(Map<String, Object> values) {
        Map<String, Object> copy = new HashMap<>(values);
        submit(() -> {
            boolean simulation = Boolean.TRUE.equals(copy.get("simulation"));
            boolean recheck = lastSimulation == null || lastSimulation != simulation;
            settings = copy;
            lastSimulation = simulation;
            if (recheck) detect();
            rescheduleProbes();
            reportStatus();
        });
    }

    public void execute(String command, Map<String, Object> args) {
        submit(() -> {
            switch (command) {
                case "pingNow":
                    runProbeCycle();
                    break;
                case "detect":
                    detect();
                    reportStatus();
                    break;
                default:
                    throw new IllegalArgumentException("Unknown command");
            }
        });
    }

    public void onEvent(String event, Map<String, Object> payload) {
        if (!"ks.device.network".equals(event)) return;
        Object upValue = payload.get("up");
        if (!(upValue instanceof Boolean)) return;
        boolean up = (Boolean) upValue;
        submit(() -> {
            networkUp = up;
            recordTransition(up);
            // The interface/gateway may have changed (e.g. WiFi to
            // Ethernet failover) — this is exactly the moment to recheck.
            detectRoute();
            reportStatus();
        });
    }

    private void detect() {
        rooted = simulation() || RootShell.isRooted();
        detectRoute();
        if (rooted && !simulation()) {
            String out = RootShell.runOutput("dumpsys wifi 2>/dev/null", RootShell.DETECT_TIMEOUT_MS);
            wifi = NetworkMath.parseDumpsysWifi(out);
        } else if (simulation()) {
            wifi = new NetworkMath.WifiSnapshot("Simulated-WiFi", "aa:bb:cc:dd:ee:ff", -55);
        }
    }

    /** Connection type and default gateway — root-free (confirmed on real
     *  hardware: an unprivileged shell can run `ip route get`). */
    private void detectRoute() {
        if (simulation()) {
            connectionType = "wifi";
            gatewayIp = "192.0.2.1";
            return;
        }
        String out = RootShell.runOutput("ip route get 1.1.1.1 2>/dev/null", RootShell.DETECT_TIMEOUT_MS);
        NetworkMath.RouteInfo route = NetworkMath.parseIpRouteGet(out);
        connectionType = NetworkMath.classifyInterface(route.iface);
        gatewayIp = route.gatewayIp;
    }

    private boolean simulation() {
        return Boolean.TRUE.equals(settings.get("simulation"));
    }

    private void recordTransition(boolean up) {
        long now = System.currentTimeMillis();
        if (!up) {
            if (openEpisodeStartMs != null) return; // already tracking this outage
            boolean merged = lastRecoveryAtMs != null
                && NetworkMath.isMergedRecovery(now - lastRecoveryAtMs);
            openEpisodeStartMs = merged ? MERGED_MARKER : now;
        } else {
            Long started = openEpisodeStartMs;
            openEpisodeStartMs = null;
            if (started == null) return; // duplicate "up" with nothing open
            lastRecoveryAtMs = now;
            if (started == MERGED_MARKER) return; // same disturbance continuing, not a new episode
            episodeStartsMs.addLast(started);
            while (episodeStartsMs.size() > MAX_RETAINED_EPISODES) episodeStartsMs.removeFirst();
        }
    }

    // A sentinel distinguishing "this open episode was merged into the prior one, and
    // must not be double-counted on recovery" from a real start timestamp — merged
    // episodes never need their exact start time since they never enter episodeStartsMs.
    private static final long MERGED_MARKER = -1L;

    private int outagesLast24h() {
        long cutoff = System.currentTimeMillis() - NetworkMath.WINDOW_MS;
        int count = 0;
        for (long t : episodeStartsMs) if (t > cutoff) count++;
        return count;
    }

    /** One shared timer drives all three latency readings — the target
     *  ping, the gateway ping, and the dashboard HTTP check — so they
     *  stay on one predictable cadence instead of three independent
     *  timers drifting apart. Always scheduled (unlike the old
     *  target-only ping): gateway ping runs whenever a gateway is known,
     *  regardless of whether a target or dashboard URL is configured. */
    private void rescheduleProbes() {
        if (probeTask != null) {
            probeTask.cancel(false);
            probeTask = null;
        }
        long intervalS = ((Number) settings.getOrDefault("pingIntervalSeconds", 30)).longValue();
        probeTask = worker.scheduleWithFixedDelay(
            () -> submit(this::runProbeCycle), 0, Math.max(10, intervalS), TimeUnit.SECONDS);
    }

    private void runProbeCycle() {
        String target = str(settings.get("pingTarget"));
        if (!target.isEmpty()) targetBurst = pingHost(target);

        String gateway = gatewayIp;
        if (gateway != null) gatewayBurst = pingHost(gateway);

        String dashboardUrl = str(settings.get("dashboardUrl"));
        if (!dashboardUrl.isEmpty()) {
            dashboardResult = simulation()
                ? DashboardProbe.Result.success(45L, 200)
                : DashboardProbe.probe(dashboardUrl);
        }
        reportStatus();
    }

    private PathBurst pingHost(String hostOrIp) {
        if (simulation()) {
            return new PathBurst(System.currentTimeMillis(), PING_ECHOES, PING_ECHOES,
                Arrays.asList(12L, 14L, 11L, 13L));
        }
        try {
            InetAddress address = InetAddress.getByName(hostOrIp);
            return icmpSource.burst(address, PING_ECHOES, PING_ECHO_TIMEOUT_MS, System::currentTimeMillis);
        } catch (Exception e) {
            return null; // unresolvable host — reported the same as "unsupported platform"
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private static String describeBurst(String label, PathBurst burst) {
        if (burst == null) return label + ": unavailable";
        if (burst.received == 0) return label + ": unreachable (" + (int) burst.lossPercent() + "% loss)";
        String loss = burst.lossPercent() > 0 ? ", " + (int) burst.lossPercent() + "% loss" : "";
        return label + ": " + Math.round(burst.avgRttMs()) + " ms" + loss;
    }

    private void reportStatus() {
        if (!alive.get()) return;
        List<String> parts = new ArrayList<>();
        String connType = connectionType;
        parts.add("Connection: " + (connType == null ? "unknown" : connType));
        parts.add("Network: " + (networkUp == null ? "unknown" : (networkUp ? "up" : "down")));
        int outages = outagesLast24h();
        if (outages > 0) {
            parts.add(outages + " outage" + (outages == 1 ? "" : "s") + " in the last 24h"
                + (outages >= NetworkMath.ATTENTION_24H ? " — link needs attention" : ""));
        }
        if (simulation()) {
            parts.add("Simulation mode");
        } else if ("wifi".equals(connType) && !rooted) {
            parts.add("WiFi signal needs root");
        } else if ("wifi".equals(connType) && wifi != null && wifi.ssid != null) {
            String detail = wifi.ssid
                + (wifi.bssid != null ? " (" + wifi.bssid + ")" : "")
                + (wifi.rssiDbm != null ? " " + wifi.rssiDbm + " dBm" : "");
            parts.add("WiFi: " + detail);
        }
        if (gatewayIp != null) parts.add(describeBurst("Gateway (" + gatewayIp + ")", gatewayBurst));
        String target = str(settings.get("pingTarget"));
        if (!target.isEmpty()) parts.add(describeBurst("Ping " + target, targetBurst));
        String dashboardUrl = str(settings.get("dashboardUrl"));
        if (!dashboardUrl.isEmpty()) {
            DashboardProbe.Result d = dashboardResult;
            if (d == null) {
                parts.add("Dashboard: pending");
            } else if (!d.ok) {
                parts.add("Dashboard: unreachable (" + d.error + ")");
            } else {
                parts.add("Dashboard: " + d.elapsedMs + " ms (HTTP " + d.httpStatus + ")");
            }
        }
        host.status(String.join(" · ", parts), false);
    }

    private interface Task { void run() throws Exception; }

    private void submit(Task task) {
        if (!alive.get()) return;
        worker.execute(() -> {
            if (!alive.get()) return;
            try {
                task.run();
            } catch (Exception e) {
                host.status(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), true);
            }
        });
    }

    public void stop() throws Exception {
        alive.set(false);
        if (probeTask != null) probeTask.cancel(false);
        worker.shutdownNow();
        worker.awaitTermination(1000, TimeUnit.MILLISECONDS);
        // KS revokes this plugin's host access before calling stop(), so no unsubscribe call here.
    }
}
