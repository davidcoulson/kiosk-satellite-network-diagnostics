// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.util.List;

/** One burst of ICMP echoes: how many were sent, how many came back, and
 *  their round-trip times in milliseconds (one entry per received echo,
 *  not per sent — a lost echo contributes nothing to this list). */
final class PathBurst {
    final long atMs;
    final int sent;
    final int received;
    final List<Long> rttsMs;

    PathBurst(long atMs, int sent, int received, List<Long> rttsMs) {
        this.atMs = atMs;
        this.sent = sent;
        this.received = received;
        this.rttsMs = rttsMs;
    }

    double lossPercent() {
        return sent <= 0 ? 0.0 : (sent - received) * 100.0 / sent;
    }

    /** Average round trip across received echoes, or -1 when none came back. */
    double avgRttMs() {
        if (rttsMs.isEmpty()) return -1.0;
        long sum = 0;
        for (long rtt : rttsMs) sum += rtt;
        return sum / (double) rttsMs.size();
    }
}
