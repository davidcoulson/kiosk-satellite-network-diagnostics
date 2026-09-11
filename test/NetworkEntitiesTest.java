// SPDX-License-Identifier: Apache-2.0
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Device-free tests for NetworkEntities.compute, reflected into since the
 * test lives outside the plugin's package (same convention as the Hello
 * World template's own test).
 */
public final class NetworkEntitiesTest {
    public static void main(String[] args) throws Exception {
        Class<?> entities = Class.forName("me.jxl.kiosk.plugins.networkdiagnostics.NetworkEntities");
        Class<?> entityClass = Class.forName("me.jxl.kiosk.plugins.networkdiagnostics.NetworkEntities$Entity");
        Method compute = entities.getDeclaredMethod("compute",
            Boolean.class, String.class,
            String.class, Integer.class,
            String.class, Double.class, Double.class, Double.class,
            String.class, Double.class, Double.class, Double.class,
            String.class, boolean.class, Long.class,
            int.class);
        compute.setAccessible(true);

        // A fully-populated reading: WiFi connected, gateway and target both
        // answering with rolling history, dashboard reachable, one outage.
        @SuppressWarnings("unchecked")
        List<Object> full = (List<Object>) compute.invoke(null,
            true, "wifi",
            "MyNetwork", -55,
            "192.0.2.1", 12.0, 0.0, 14.0,
            "192.0.2.50", 20.0, 5.0, 22.0,
            "https://example.test", true, 45L,
            1);
        Map<String, Object> byId = idMap(full, entityClass);
        assertTrue(byId.containsKey("binary_sensor.network_up"), "network_up published");
        assertEquals(true, field(byId.get("binary_sensor.network_up"), "state", entityClass), "network up state");
        assertEquals("WiFi", field(byId.get("text_sensor.connection_type"), "state", entityClass), "friendly connection type");
        assertEquals(-55.0, field(byId.get("sensor.wifi_rssi"), "state", entityClass), "wifi rssi as Double");
        assertEquals("MyNetwork", field(byId.get("text_sensor.wifi_ssid"), "state", entityClass), "wifi ssid");
        assertEquals(12.0, field(byId.get("sensor.gateway_latency_ms"), "state", entityClass), "gateway latency");
        assertEquals(0.0, field(byId.get("sensor.gateway_loss_percent"), "state", entityClass), "gateway loss");
        assertEquals(14.0, field(byId.get("sensor.gateway_p95_ms"), "state", entityClass), "gateway p95");
        assertEquals(20.0, field(byId.get("sensor.target_latency_ms"), "state", entityClass), "target latency");
        assertEquals(5.0, field(byId.get("sensor.target_loss_percent"), "state", entityClass), "target loss");
        assertEquals(22.0, field(byId.get("sensor.target_p95_ms"), "state", entityClass), "target p95");
        assertEquals(45.0, field(byId.get("sensor.dashboard_response_ms"), "state", entityClass), "dashboard response time");
        assertEquals(1.0, field(byId.get("sensor.outages_24h"), "state", entityClass), "outage count");
        assertEquals(12, full.size(), "every applicable reading produced exactly one entity");

        // A minimal, mostly-unconfigured reading: nothing pinged, no dashboard
        // URL, not on WiFi — only the always-present entities remain.
        @SuppressWarnings("unchecked")
        List<Object> minimal = (List<Object>) compute.invoke(null,
            (Object) null, "ethernet",
            null, null,
            null, null, null, null,
            "", null, null, null,
            "", false, null,
            0);
        Map<String, Object> minimalIds = idMap(minimal, entityClass);
        assertEquals(3, minimal.size(), "only network_up, connection_type and outages_24h remain");
        assertTrue(minimalIds.containsKey("binary_sensor.network_up"), "network_up always present");
        assertEquals(null, field(minimalIds.get("binary_sensor.network_up"), "state", entityClass), "unknown network state is null, not false");
        assertEquals("Ethernet", field(minimalIds.get("text_sensor.connection_type"), "state", entityClass), "ethernet friendly name");
        assertTrue(!minimalIds.containsKey("sensor.wifi_rssi"), "no wifi_rssi when not on wifi");
        assertTrue(!minimalIds.containsKey("sensor.gateway_latency_ms"), "no gateway sensors with no gateway");
        assertTrue(!minimalIds.containsKey("sensor.target_latency_ms"), "no target sensors with an empty ping target");
        assertTrue(!minimalIds.containsKey("sensor.dashboard_response_ms"), "no dashboard sensor with an empty dashboard URL");

        // A gateway/target configured but currently unreachable: burst avg
        // is null (all echoes lost), but p95/miss history can still exist.
        @SuppressWarnings("unchecked")
        List<Object> unreachable = (List<Object>) compute.invoke(null,
            false, "wifi",
            null, null,
            "192.0.2.1", null, null, 14.0,
            "192.0.2.50", null, null, null,
            "https://example.test", false, null,
            0);
        Map<String, Object> unreachableIds = idMap(unreachable, entityClass);
        assertTrue(!unreachableIds.containsKey("sensor.gateway_latency_ms"), "no latency reading while every echo was lost");
        assertTrue(unreachableIds.containsKey("sensor.gateway_p95_ms"), "p95 history survives a currently-unreachable burst");
        assertTrue(!unreachableIds.containsKey("sensor.target_p95_ms"), "no p95 yet with no history at all");
        assertTrue(!unreachableIds.containsKey("sensor.dashboard_response_ms"), "a failed dashboard probe publishes nothing, not a stale reading");

        // friendlyConnectionType directly.
        Method friendly = entities.getDeclaredMethod("friendlyConnectionType", String.class);
        friendly.setAccessible(true);
        assertEquals("WiFi", friendly.invoke(null, "wifi"), "wifi friendly name");
        assertEquals("Ethernet", friendly.invoke(null, "ethernet"), "ethernet friendly name");
        assertEquals("Other (usb0)", friendly.invoke(null, "other:usb0"), "other interface friendly name");
        assertEquals(null, friendly.invoke(null, (Object) null), "null connection type stays null");

        System.out.println("PASS: entity computation for full/minimal/unreachable readings, connection-type friendly names.");
    }

    private static Map<String, Object> idMap(List<Object> entities, Class<?> entityClass) throws Exception {
        Method idMethod = entityClass.getDeclaredMethod("id");
        idMethod.setAccessible(true);
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        for (Object e : entities) map.put((String) idMethod.invoke(e), e);
        return map;
    }

    private static Object field(Object entity, String name, Class<?> entityClass) throws Exception {
        java.lang.reflect.Field f = entityClass.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(entity);
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!objectsEquals(expected, actual)) {
            throw new AssertionError(message + " — expected " + expected + " but got " + actual);
        }
    }

    private static boolean objectsEquals(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
    }
}
