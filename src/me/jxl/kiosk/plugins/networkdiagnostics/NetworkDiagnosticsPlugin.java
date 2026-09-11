// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * WiFi signal (root, `dumpsys wifi`), network outage history (root-free —
 * driven by the host's own {@code device.network} event), and layer-3
 * latency/loss probing (root-free — an unprivileged ICMP socket, the same
 * mechanism Android's own `ping` binary uses).
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
    private ScheduledFuture<?> pingTask;

    // Latest observations, for status reporting.
    private volatile Boolean networkUp;
    private volatile NetworkMath.WifiSnapshot wifi;
    private volatile PathBurst lastBurst;

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
            reschedulePing();
            reportStatus();
        });
    }

    public void execute(String command, Map<String, Object> args) {
        submit(() -> {
            switch (command) {
                case "pingNow":
                    runPing();
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
            reportStatus();
        });
    }

    private void detect() {
        rooted = simulation() || RootShell.isRooted();
        if (rooted && !simulation()) {
            String out = RootShell.runOutput("dumpsys wifi 2>/dev/null", RootShell.DETECT_TIMEOUT_MS);
            wifi = NetworkMath.parseDumpsysWifi(out);
        } else if (simulation()) {
            wifi = new NetworkMath.WifiSnapshot("Simulated-WiFi", -55);
        }
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

    private void reschedulePing() {
        if (pingTask != null) {
            pingTask.cancel(false);
            pingTask = null;
        }
        String target = str(settings.get("pingTarget"));
        if (target.isEmpty()) return;
        long intervalS = ((Number) settings.getOrDefault("pingIntervalSeconds", 30)).longValue();
        pingTask = worker.scheduleWithFixedDelay(
            () -> submit(this::runPing), 0, Math.max(10, intervalS), TimeUnit.SECONDS);
    }

    private void runPing() {
        String target = str(settings.get("pingTarget"));
        if (target.isEmpty()) return;
        if (simulation()) {
            lastBurst = new PathBurst(System.currentTimeMillis(), PING_ECHOES, PING_ECHOES,
                Arrays.asList(12L, 14L, 11L, 13L));
            reportStatus();
            return;
        }
        InetAddress address;
        try {
            address = InetAddress.getByName(target);
        } catch (Exception e) {
            host.status("Ping target \"" + target + "\" could not be resolved.", true);
            return;
        }
        PathBurst burst = icmpSource.burst(address, PING_ECHOES, PING_ECHO_TIMEOUT_MS, System::currentTimeMillis);
        lastBurst = burst;
        reportStatus();
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }

    private void reportStatus() {
        if (!alive.get()) return;
        List<String> parts = new ArrayList<>();
        parts.add("Network: " + (networkUp == null ? "unknown" : (networkUp ? "up" : "down")));
        int outages = outagesLast24h();
        if (outages > 0) {
            parts.add(outages + " outage" + (outages == 1 ? "" : "s") + " in the last 24h"
                + (outages >= NetworkMath.ATTENTION_24H ? " — link needs attention" : ""));
        }
        if (simulation()) {
            parts.add("Simulation mode");
        } else if (!rooted) {
            parts.add("WiFi signal needs root");
        } else if (wifi != null && wifi.ssid != null) {
            parts.add("WiFi: " + wifi.ssid + (wifi.rssiDbm != null ? " (" + wifi.rssiDbm + " dBm)" : ""));
        } else if (rooted) {
            parts.add("WiFi: not connected");
        }
        PathBurst burst = lastBurst;
        String target = str(settings.get("pingTarget"));
        if (!target.isEmpty()) {
            if (burst == null) {
                parts.add("Ping " + target + ": pending");
            } else if (burst.received == 0) {
                parts.add("Ping " + target + ": unreachable (" + (int) burst.lossPercent() + "% loss)");
            } else {
                parts.add("Ping " + target + ": " + Math.round(burst.avgRttMs()) + " ms"
                    + (burst.lossPercent() > 0 ? ", " + (int) burst.lossPercent() + "% loss" : ""));
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
        if (pingTask != null) pingTask.cancel(false);
        worker.shutdownNow();
        worker.awaitTermination(1000, TimeUnit.MILLISECONDS);
        // KS revokes this plugin's host access before calling stop(), so no unsubscribe call here.
    }
}
