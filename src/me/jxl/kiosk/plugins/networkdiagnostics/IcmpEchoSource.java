// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Layer-3 echoes from this process's own UID, with no root, no subprocess
 * and no second TCP connection — ported from ha-paneld's IcmpEchoSource.kt.
 *
 * Linux grants unprivileged ICMP datagram sockets to any GID inside
 * `net.ipv4.ping_group_range`, which Android sets wide open; this is the
 * same mechanism the platform's own `ping` binary uses, and that binary
 * is not setuid. What the kernel permits is not the whole story though:
 * an OEM SELinux policy can still deny `untrusted_app` the `icmp_socket`
 * class, and that denial arrives as an ErrnoException from Os.socket. It
 * is reported as "unsupported" ({@link #burst} returning null) and never
 * as packet loss — the caller keeps whatever verdict it can derive from
 * {@code device.network} events instead.
 *
 * Three details of the unprivileged ping socket differ from the raw-
 * socket code most examples show, and each one silently breaks matching
 * if missed: the kernel REWRITES the echo identifier to the socket's own
 * port, so replies are matched on the sequence number and a per-burst
 * random token in the payload, never on the identifier; a received
 * datagram begins at the ICMP header, with no IP header in front of it;
 * and the socket is connected first, so only the target's replies are
 * delivered to it.
 */
final class IcmpEchoSource {
    private static final int MAX_DATAGRAM = 1500;
    private final SecureRandom random = new SecureRandom();

    /**
     * Sends [echoes] echo requests to [target] and returns what came
     * back, or null when this platform will not let this process send
     * them at all — the caller must treat null as "this panel cannot
     * measure layer 3", never as a fault in the network. Any other
     * outcome, including every echo being lost, is a real [PathBurst].
     */
    PathBurst burst(InetAddress target, int echoes, long perEchoTimeoutMs, LongSupplier nowMs) {
        try {
            return attemptBurst(target, echoes, perEchoTimeoutMs, nowMs);
        } catch (Throwable t) {
            // Every failure of the complete operation means the same thing: this platform
            // will not let this process measure layer 3. A diagnostic must never be able to
            // crash the app it is diagnosing, so the boundary is around the whole operation
            // rather than just the socket call — a refusal peculiar to one OEM can surface
            // from connect, poll, read, or here just as easily as from socket().
            return null;
        }
    }

    private PathBurst attemptBurst(InetAddress target, int echoes, long perEchoTimeoutMs, LongSupplier nowMs) throws Exception {
        boolean v6 = target instanceof Inet6Address;
        FileDescriptor fd;
        try {
            fd = Os.socket(
                v6 ? OsConstants.AF_INET6 : OsConstants.AF_INET,
                OsConstants.SOCK_DGRAM,
                v6 ? OsConstants.IPPROTO_ICMPV6 : OsConstants.IPPROTO_ICMP);
        } catch (ErrnoException | SecurityException e) {
            return null;
        }

        int received = 0;
        List<Long> rtts = new ArrayList<>(echoes);
        int token = random.nextInt();
        try {
            try {
                Os.connect(fd, target, 0);
            } catch (ErrnoException e) {
                // No route at all: a real path failure, and a burst that lost everything says so.
                return new PathBurst(nowMs.getAsLong(), echoes, 0, Collections.emptyList());
            }
            for (int seq = 1; seq <= echoes; seq++) {
                if (sendOne(fd, v6, seq, token)) {
                    long sentAt = nowMs.getAsLong();
                    long rtt = awaitReply(fd, v6, seq, token, perEchoTimeoutMs, sentAt, nowMs);
                    if (rtt >= 0L) {
                        received++;
                        rtts.add(rtt);
                    }
                }
            }
        } finally {
            try {
                Os.close(fd);
            } catch (ErrnoException ignored) {}
        }
        return new PathBurst(nowMs.getAsLong(), echoes, received, rtts);
    }

    /** True when the request went out; a send error is an unanswered echo, not a thrown burst. */
    private boolean sendOne(FileDescriptor fd, boolean v6, int seq, int token) {
        byte[] packet = IcmpEchoPacket.request(v6, seq, token);
        try {
            return Os.write(fd, ByteBuffer.wrap(packet)) > 0;
        } catch (ErrnoException | IOException e) {
            return false;
        }
    }

    /**
     * Waits for the reply to [seq], returning its round trip or -1.
     *
     * Replies for an ABANDONED earlier echo can arrive here; they are
     * read, ignored, and the wait continues on the remaining budget,
     * because attributing a late reply to the wrong sequence would
     * report a round trip that never happened.
     */
    private long awaitReply(FileDescriptor fd, boolean v6, int seq, int token, long timeoutMs, long sentAtMs, LongSupplier nowMs) {
        long deadline = sentAtMs + timeoutMs;
        ByteBuffer buffer = ByteBuffer.allocate(MAX_DATAGRAM);
        while (true) {
            long remaining = deadline - nowMs.getAsLong();
            if (remaining <= 0L) return -1L;
            StructPollfd poll = new StructPollfd();
            poll.fd = fd;
            poll.events = (short) OsConstants.POLLIN;
            int ready;
            try {
                ready = Os.poll(new StructPollfd[]{poll}, (int) Math.min(remaining, Integer.MAX_VALUE));
            } catch (ErrnoException e) {
                return -1L;
            }
            if (ready <= 0) return -1L;
            buffer.clear();
            int read;
            try {
                read = Os.read(fd, buffer);
            } catch (ErrnoException | IOException e) {
                // An ICMP error (host or network unreachable) is delivered to the socket as
                // an error on receive. The echo is lost, which is exactly what this records.
                return -1L;
            }
            long at = nowMs.getAsLong();
            if (read <= 0) continue;
            // The datagram starts at the ICMP header: no IP header is present on a ping socket.
            if (IcmpEchoPacket.matches(buffer.array(), read, v6, seq, token)) {
                return Math.max(at - sentAtMs, 0L);
            }
        }
    }
}
