import java.util.*;
import java.util.concurrent.*;
import me.jxl.kiosk.plugins.PluginHost;
import me.jxl.kiosk.plugins.networkdiagnostics.NetworkDiagnosticsPlugin;

/** A host starting without Shizuku must recover on a normal probe cycle. */
public final class ShizukuReconnectTest {
    static final class Host implements PluginHost {
        volatile boolean ready;
        volatile Double signal;
        volatile boolean compactRead;
        final BlockingQueue<Double> signals = new LinkedBlockingQueue<>();
        final BlockingQueue<String> states = new LinkedBlockingQueue<>();
        public Map<String,Object> shizukuState() {
            Map<String,Object> s = new HashMap<>();
            s.put("granted", ready); s.put("available", ready); s.put("uid", 2000);
            return s;
        }
        public void executeShizuku(String[] c, int timeout, CommandCallback cb) {
            Map<String,Object> result = new HashMap<>();
            result.put("exitCode", 0);
            if (c[2].startsWith("cmd wifi status")) compactRead = true;
            result.put("stdout", c[2].startsWith("ip route")
                ? "1.1.1.1 dev wlan0 src 192.0.2.21 uid 2000"
                : "Wifi is connected to \"Not Connected\"\nWifiInfo: SSID: Not Connected, BSSID: 02:00:00:00:00:01, RSSI: -44, Link speed: 866Mbps");
            cb.onResult(ready, result, ready ? null : "unavailable");
        }
        public void executeCommand(String c, Map<String,Object> a, CommandCallback cb) {
            if (c.equals("getNetworkLink") && ready) {
                Map<String,Object> link=new HashMap<>(); link.put("type","wifi"); link.put("rssi",-44);
                cb.onResult(true,link,null);
            } else cb.onResult(false,null,"unused");
        }
        public void subscribe(String e) {}
        public void showWindow(String a,String b,String c) {}
        public void hideWindow() {}
        public void log(String s) {}
        public void status(String s,boolean e) { states.add(s); }
        public void publishTextSensor(String k,String n,String s) {}
        public void publishBinarySensor(String k,String n,String d,Boolean s) {}
        public void publishSensor(String k,String n,Map<String,Object> m,Double s) { if (k.equals("wifi_rssi")) { signal=s; if(s!=null) signals.add(s); } }
        public void removeSensor(String k) {}
        public void removeTextSensor(String k) {}
        public void removeBinarySensor(String k) {}
        public void removeSeries(String k) {}
    }
    static void expect(Host h, String expected) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<deadline) {
            String s=h.states.poll(1,TimeUnit.SECONDS);
            if(s!=null && s.contains(expected)) return;
        }
        throw new AssertionError("Missing status: "+expected);
    }
    public static void main(String[] args) throws Exception {
        Host h=new Host(); NetworkDiagnosticsPlugin p=new NetworkDiagnosticsPlugin();
        Map<String,Object> settings=new HashMap<>(); settings.put("pingIntervalSeconds",300);
        try {
            p.start(h,settings); expect(h,"Connection: unknown");
            h.states.clear(); h.ready=true;
            p.execute("pingNow",Collections.emptyMap()); expect(h,"Connection: wifi");
            if (!Double.valueOf(-44).equals(h.signals.poll(5,TimeUnit.SECONDS)) || !h.compactRead) throw new AssertionError("Compact WiFi status must publish actual RSSI");
            h.states.clear(); h.ready=false;
            p.execute("pingNow",Collections.emptyMap()); expect(h,"Connection: unknown");
            h.states.clear(); h.ready=true;
            p.execute("pingNow",Collections.emptyMap()); expect(h,"Connection: wifi");
            System.out.println("Shizuku reconnect probe test passed");
        } finally { p.stop(); }
    }
}
