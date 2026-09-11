// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A rolling p95 latency + miss-rate tracker for one pinged target —
 * ha-paneld's own runtime diagnostics reports exactly this shape
 * ("healthy; p95 5 ms, no misses in the last 5 min"): a single burst's
 * RTT is noisy, but p95 over recent history is stable enough to alert on,
 * and a miss count over a trailing window distinguishes "briefly
 * unreachable a while ago" from "unreachable right now."
 *
 * One instance per target (gateway and the configured ping target get
 * their own); in-memory only, resets on plugin restart — same convention
 * as this plugin's outage tracker (no Context, nowhere durable to persist).
 */
final class LatencyTracker {
    private static final int MAX_SAMPLES = 60;
    static final long MISS_WINDOW_MS = 5 * 60_000L;

    private final Deque<Double> rttSamplesMs = new ArrayDeque<>();
    private final Deque<Long> missAtMs = new ArrayDeque<>();

    /** Records one probe outcome. A null or fully-lost burst counts as a
     *  miss; anything with at least one reply contributes its average RTT
     *  to the latency history — a burst can be a partial miss (some loss,
     *  some replies) without being a "miss" for this purpose. */
    void record(PathBurst burst) {
        if (burst == null || burst.received == 0) {
            missAtMs.addLast(System.currentTimeMillis());
            while (missAtMs.size() > MAX_SAMPLES) missAtMs.removeFirst();
            return;
        }
        rttSamplesMs.addLast(burst.avgRttMs());
        while (rttSamplesMs.size() > MAX_SAMPLES) rttSamplesMs.removeFirst();
    }

    /** The 95th percentile RTT (ms) over retained history, or -1 with no
     *  samples yet. */
    double p95Ms() {
        return NetworkMath.percentile(new ArrayList<>(rttSamplesMs), 95.0);
    }

    /** How many misses landed within the trailing {@link #MISS_WINDOW_MS}
     *  — a pure read (filter, not delete), so this never destroys history
     *  a later read might still want. */
    int missesInWindow() {
        long cutoff = System.currentTimeMillis() - MISS_WINDOW_MS;
        int count = 0;
        for (long t : missAtMs) if (t > cutoff) count++;
        return count;
    }

    boolean hasSamples() {
        return !rttSamplesMs.isEmpty();
    }
}
