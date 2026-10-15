// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;

public final class WifiHealthTest {
    public static void main(String[] args) throws Exception {
        Method parse = Class.forName("me.jxl.kiosk.plugins.networkdiagnostics.NetworkMath")
            .getDeclaredMethod("parseHealthRssi", String.class);
        parse.setAccessible(true);
        expect(parse, "{\"link\":{\"type\":\"wifi\",\"rssi\":-44,\"speedMbps\":866}}", -44);
        expect(parse, "{\"link\": {\"rssi\": -47, \"type\": \"wifi\"}}", -47);
        expect(parse, "{\"link\":{\"type\":\"ethernet\",\"rssi\":-44}}", null);
        expect(parse, "{\"link\":{\"type\":\"wifi\",\"rssi\":null}}", null);
        expect(parse, "{\"link\":{\"type\":\"wifi\",\"rssi\":-127}}", null);
        expect(parse, "{\"link\":{\"type\":\"wifi\",\"rssi\":2147483647}}", null);
        expect(parse, "{\"rssi\":-10,\"link\":{\"type\":\"wifi\"}}", null);
        expect(parse, "{\"link\":{\"type\":\"wifi\",\"rssi\":-44.5}}", null);
        expect(parse, "{\"link\":null}", null);
        expect(parse, null, null);
        System.out.println("PASS: current WiFi health RSSI and missing/invalid/non-WiFi data");
    }
    private static void expect(Method parse, String json, Integer wanted) throws Exception {
        Object got = parse.invoke(null, json);
        if (!java.util.Objects.equals(wanted, got)) throw new AssertionError("Expected " + wanted + ", got " + got);
    }
}
