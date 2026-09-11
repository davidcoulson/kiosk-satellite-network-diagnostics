// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.nio.ByteBuffer;

/**
 * ICMP echo wire format, ported nearly verbatim from ha-paneld's
 * IcmpEchoPacket.kt — the tricky, easy-to-get-silently-wrong parts of an
 * unprivileged ping socket, kept apart from the syscalls so they can be
 * proven without a device (see test/IcmpEchoPacketTest.java): a reply is
 * matched on sequence number and an echoed random token because the
 * kernel REWRITES the identifier to the socket's own port; a received
 * datagram is parsed starting at the ICMP header, since there's no IP
 * header in front of it on a datagram socket; and the IPv4 checksum is
 * computed over the whole packet with its own checksum field zeroed
 * (ICMPv6's checksum is computed by the kernel instead).
 */
final class IcmpEchoPacket {
    private IcmpEchoPacket() {}

    static final int HEADER_BYTES = 8;
    static final int PAYLOAD_BYTES = 16;
    static final int ICMP_ECHO_REQUEST = 8;
    static final int ICMP_ECHO_REPLY = 0;
    static final int ICMPV6_ECHO_REQUEST = 128;
    static final int ICMPV6_ECHO_REPLY = 129;

    /** Builds an echo request carrying [seq] and [token]. */
    static byte[] request(boolean v6, int seq, int token) {
        byte[] packet = new byte[HEADER_BYTES + PAYLOAD_BYTES];
        packet[0] = (byte) (v6 ? ICMPV6_ECHO_REQUEST : ICMP_ECHO_REQUEST);
        packet[1] = 0;
        packet[2] = 0; // checksum, filled below for IPv4; the kernel owns it for ICMPv6
        packet[3] = 0;
        packet[4] = 0; // identifier: the kernel overwrites this with the socket's port
        packet[5] = 0;
        packet[6] = (byte) ((seq >> 8) & 0xFF);
        packet[7] = (byte) (seq & 0xFF);
        ByteBuffer.wrap(packet, HEADER_BYTES, 4).putInt(token);
        if (!v6) {
            int sum = checksum(packet);
            packet[2] = (byte) ((sum >> 8) & 0xFF);
            packet[3] = (byte) (sum & 0xFF);
        }
        return packet;
    }

    /** Whether [bytes] (length [length]) is the reply to [seq] carrying [token]. The
     *  identifier is never compared — the kernel rewrites it. */
    static boolean matches(byte[] bytes, int length, boolean v6, int seq, int token) {
        if (length < HEADER_BYTES + 4) return false;
        int expectedType = v6 ? ICMPV6_ECHO_REPLY : ICMP_ECHO_REPLY;
        if ((bytes[0] & 0xFF) != expectedType) return false;
        int replySeq = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        if (replySeq != (seq & 0xFFFF)) return false;
        return ByteBuffer.wrap(bytes, HEADER_BYTES, 4).getInt() == token;
    }

    /** Standard internet checksum. The kernel recomputes it, but a correct one costs nothing. */
    static int checksum(byte[] bytes) {
        long sum = 0;
        int i = 0;
        while (i + 1 < bytes.length) {
            sum += ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
            i += 2;
        }
        if (i < bytes.length) sum += (bytes[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }
}
