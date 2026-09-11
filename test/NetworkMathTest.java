// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Device-free tests for NetworkMath and IcmpEchoPacket's package-private
 *  static methods, reflected into since this test lives outside the
 *  plugin's package (same convention as the Hello World template's own
 *  test). No socket, no root, no network — pure parsing/math only. */
public final class NetworkMathTest {
    public static void main(String[] args) throws Exception {
        Class<?> math = Class.forName("me.jxl.kiosk.plugins.networkdiagnostics.NetworkMath");

        Method normalizedSsid = math.getDeclaredMethod("normalizedSsid", String.class);
        normalizedSsid.setAccessible(true);
        assertEquals("MyNetwork", normalizedSsid.invoke(null, "\"MyNetwork\""), "quoted SSID unwrapped");
        assertEquals("MyNetwork", normalizedSsid.invoke(null, "MyNetwork"), "unquoted SSID passed through");
        assertNull(normalizedSsid.invoke(null, "<unknown ssid>"), "the unknown-ssid placeholder is null");
        assertNull(normalizedSsid.invoke(null, "UNKNOWN SSID"), "the placeholder is matched case-insensitively");
        assertNull(normalizedSsid.invoke(null, ""), "empty string is null");
        assertNull(normalizedSsid.invoke(null, (Object) null), "null passes through as null");

        Method normalizedRssi = math.getDeclaredMethod("normalizedRssi", int.class);
        normalizedRssi.setAccessible(true);
        assertEquals(-55, normalizedRssi.invoke(null, -55), "a plausible RSSI is kept");
        assertEquals(0, normalizedRssi.invoke(null, 0), "0 dBm is a valid (if implausible) boundary");
        assertNull(normalizedRssi.invoke(null, 1), "a positive value is rejected");
        assertNull(normalizedRssi.invoke(null, -200), "a value below the radio floor is rejected");

        Method parseDumpsys = math.getDeclaredMethod("parseDumpsysWifi", String.class);
        parseDumpsys.setAccessible(true);
        Class<?> snapshotClass = Class.forName("me.jxl.kiosk.plugins.networkdiagnostics.NetworkMath$WifiSnapshot");
        Field ssidField = snapshotClass.getDeclaredField("ssid");
        ssidField.setAccessible(true);
        Field rssiField = snapshotClass.getDeclaredField("rssiDbm");
        rssiField.setAccessible(true);

        String connected = "Wi-Fi is connected to \"HomeNet\"\nRSSI: -48\nLink speed: 400Mbps";
        Object s1 = parseDumpsys.invoke(null, connected);
        assertEquals("HomeNet", ssidField.get(s1), "SSID parsed from the connected-to line");
        assertEquals(-48, rssiField.get(s1), "RSSI parsed from its own line");

        String wifiInfoStyle = "mWifiInfo SSID: OfficeNet, BSSID: 00:11:22:33:44:55, RSSI: -62";
        Object s2 = parseDumpsys.invoke(null, wifiInfoStyle);
        assertEquals("OfficeNet", ssidField.get(s2), "SSID parsed from the WifiInfo-style line");
        assertEquals(-62, rssiField.get(s2), "RSSI still parsed alongside the WifiInfo-style SSID");

        Object s3 = parseDumpsys.invoke(null, "Wi-Fi is disabled");
        assertNull(ssidField.get(s3), "no match yields a null SSID, not a crash");
        assertNull(rssiField.get(s3), "no match yields a null RSSI");

        Object s4 = parseDumpsys.invoke(null, (Object) null);
        assertNull(ssidField.get(s4), "null input is handled without an exception");

        Method isMerged = math.getDeclaredMethod("isMergedRecovery", Long.class);
        isMerged.setAccessible(true);
        assertTrue((Boolean) isMerged.invoke(null, 500L), "a quick re-loss is merged");
        assertTrue((Boolean) isMerged.invoke(null, 10_000L), "exactly at the merge window boundary is merged");
        assertFalse((Boolean) isMerged.invoke(null, 10_001L), "just past the merge window is a new episode");
        assertFalse((Boolean) isMerged.invoke(null, (Object) null), "no prior recovery can never be a merge");

        // IcmpEchoPacket: wire format round-trips and rejects a mismatched reply.
        Class<?> icmp = Class.forName("me.jxl.kiosk.plugins.networkdiagnostics.IcmpEchoPacket");
        Method request = icmp.getDeclaredMethod("request", boolean.class, int.class, int.class);
        request.setAccessible(true);
        Method matches = icmp.getDeclaredMethod("matches", byte[].class, int.class, boolean.class, int.class, int.class);
        matches.setAccessible(true);

        byte[] echoRequest = (byte[]) request.invoke(null, false, 7, 0x1234);
        // A real reply has type 0 (ICMP_ECHO_REPLY) instead of the request's type 8; everything else
        // — sequence and token position — is identical, since the kernel rewrites only the identifier.
        byte[] reply = echoRequest.clone();
        reply[0] = 0;
        assertTrue((Boolean) matches.invoke(null, reply, reply.length, false, 7, 0x1234), "a correctly-shaped reply matches");
        assertFalse((Boolean) matches.invoke(null, reply, reply.length, false, 8, 0x1234), "a different sequence does not match");
        assertFalse((Boolean) matches.invoke(null, reply, reply.length, false, 7, 0x9999), "a different token does not match");
        assertFalse((Boolean) matches.invoke(null, echoRequest, echoRequest.length, false, 7, 0x1234), "an unmodified request (still type 8) is not a reply");
        assertFalse((Boolean) matches.invoke(null, new byte[]{0, 0}, 2, false, 7, 0x1234), "a too-short datagram is never a match");

        System.out.println("PASS: SSID/RSSI normalization, dumpsys wifi parsing, outage merge-window logic, ICMP wire format.");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("expected true: " + message);
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) throw new AssertionError("expected false: " + message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!objectsEquals(expected, actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }

    private static void assertNull(Object actual, String message) {
        if (actual != null) throw new AssertionError(message + " — expected null but got " + actual);
    }

    private static boolean objectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
