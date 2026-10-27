package vn.vuumi.pcdash.remote;

public class MetricsParserTest {

    public static void main(String[] args) {
        System.out.println("Running MetricsParserTest...");
        testFullValidMetrics();
        testMissingFields();
        testNullInputAndFields();
        testWrongTypes();
        testTooDeep();
        testTooLarge();
        testLongStrings();
        System.out.println("ALL MetricsParserTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testFullValidMetrics() {
        String json = "{\n"
                + "  \"ts\": 1792480000000,\n"
                + "  \"pc\": {\n"
                + "    \"online\": true,\n"
                + "    \"cpu\": {\"percent\": 12.3, \"tempC\": 54.0, \"name\": \"AMD Ryzen ...\"},\n"
                + "    \"ram\": {\"usedMB\": 8123, \"totalMB\": 32768, \"percent\": 24.8},\n"
                + "    \"gpu\": {\"percent\": 5.0, \"tempC\": 40.0, \"memUsedMB\": 1200, \"memTotalMB\": 8192, \"name\": \"NVIDIA ...\"},\n"
                + "    \"net\": {\"downBps\": 123456, \"upBps\": 2345},\n"
                + "    \"disks\": [{\"name\": \"C:\", \"usedGB\": 210.5, \"totalGB\": 476.0}],\n"
                + "    \"uptimeSec\": 12345,\n"
                + "    \"sys\": {\"tempC\": 47.8}\n"
                + "  },\n"
                + "  \"phone\": {\n"
                + "    \"battery\": {\"percent\": 100, \"charging\": true, \"tempC\": 31.5},\n"
                + "    \"ram\": {\"availMB\": 2048, \"totalMB\": 6144},\n"
                + "    \"storage\": {\"freeGB\": 80.2, \"totalGB\": 128.0},\n"
                + "    \"wifi\": {\"rssiDbm\": -55, \"linkMbps\": 866},\n"
                + "    \"net\": {\"downBps\": 1000, \"upBps\": 500},\n"
                + "    \"tailscale\": true,\n"
                + "    \"thermal\": \"NONE\",\n"
                + "    \"uptimeSec\": 99999\n"
                + "  }\n"
                + "}";

        MetricsSnapshot s = MetricsParser.parse(json);
        assertTrue(s != null, "snapshot not null");
        assertTrue(Long.valueOf(1792480000000L).equals(s.ts), "ts matches");

        // PC checks
        assertTrue(s.pc != null, "pc not null");
        assertTrue(Boolean.TRUE.equals(s.pc.online), "pc online true");
        assertTrue(s.pc.cpu != null, "cpu not null");
        assertTrue(Double.valueOf(12.3).equals(s.pc.cpu.percent), "cpu percent");
        assertTrue(Double.valueOf(54.0).equals(s.pc.cpu.tempC), "cpu tempC");
        assertTrue("AMD Ryzen ...".equals(s.pc.cpu.name), "cpu name");

        assertTrue(s.pc.ram != null, "ram not null");
        assertTrue(Long.valueOf(8123L).equals(s.pc.ram.usedMB), "ram usedMB");
        assertTrue(Long.valueOf(32768L).equals(s.pc.ram.totalMB), "ram totalMB");
        assertTrue(Double.valueOf(24.8).equals(s.pc.ram.percent), "ram percent");

        assertTrue(s.pc.gpu != null, "gpu not null");
        assertTrue(Double.valueOf(5.0).equals(s.pc.gpu.percent), "gpu percent");
        assertTrue(Double.valueOf(40.0).equals(s.pc.gpu.tempC), "gpu tempC");
        assertTrue(Long.valueOf(1200L).equals(s.pc.gpu.memUsedMB), "gpu memUsedMB");
        assertTrue(Long.valueOf(8192L).equals(s.pc.gpu.memTotalMB), "gpu memTotalMB");
        assertTrue("NVIDIA ...".equals(s.pc.gpu.name), "gpu name");

        assertTrue(s.pc.net != null, "net not null");
        assertTrue(Long.valueOf(123456L).equals(s.pc.net.downBps), "net downBps");
        assertTrue(Long.valueOf(2345L).equals(s.pc.net.upBps), "net upBps");

        assertTrue(s.pc.disks != null && s.pc.disks.size() == 1, "disks size 1");
        assertTrue("C:".equals(s.pc.disks.get(0).name), "disk name");
        assertTrue(Double.valueOf(210.5).equals(s.pc.disks.get(0).usedGB), "disk usedGB");
        assertTrue(Double.valueOf(476.0).equals(s.pc.disks.get(0).totalGB), "disk totalGB");
        assertTrue(Long.valueOf(12345L).equals(s.pc.uptimeSec), "pc uptimeSec");
        assertTrue(s.pc.sys != null && Double.valueOf(47.8).equals(s.pc.sys.tempC), "pc sys tempC");
        // Phone checks
        assertTrue(s.phone != null, "phone not null");
        assertTrue(s.phone.battery != null, "battery not null");
        assertTrue(Double.valueOf(100.0).equals(s.phone.battery.percent), "battery percent");
        assertTrue(Boolean.TRUE.equals(s.phone.battery.charging), "battery charging");
        assertTrue(Double.valueOf(31.5).equals(s.phone.battery.tempC), "battery tempC");

        assertTrue(s.phone.ram != null, "phone ram not null");
        assertTrue(Long.valueOf(2048L).equals(s.phone.ram.availMB), "phone ram availMB");
        assertTrue(Long.valueOf(6144L).equals(s.phone.ram.totalMB), "phone ram totalMB");

        assertTrue(s.phone.storage != null, "storage not null");
        assertTrue(Double.valueOf(80.2).equals(s.phone.storage.freeGB), "storage freeGB");
        assertTrue(Double.valueOf(128.0).equals(s.phone.storage.totalGB), "storage totalGB");

        assertTrue(s.phone.wifi != null, "wifi not null");
        assertTrue(Integer.valueOf(-55).equals(s.phone.wifi.rssiDbm), "wifi rssiDbm");
        assertTrue(Integer.valueOf(866).equals(s.phone.wifi.linkMbps), "wifi linkMbps");

        assertTrue(s.phone.net != null, "phone net not null");
        assertTrue(Long.valueOf(1000L).equals(s.phone.net.downBps), "phone net downBps");
        assertTrue(Long.valueOf(500L).equals(s.phone.net.upBps), "phone net upBps");

        assertTrue(Boolean.TRUE.equals(s.phone.tailscale), "tailscale true");
        assertTrue("NONE".equals(s.phone.thermal), "thermal NONE");
        assertTrue(Long.valueOf(99999L).equals(s.phone.uptimeSec), "phone uptimeSec");

        System.out.println("PASS: testFullValidMetrics");
    }

    public static void testMissingFields() {
        String json = "{\"ts\": 12345, \"pc\": {\"online\": false}}";
        MetricsSnapshot s = MetricsParser.parse(json);
        assertTrue(s != null, "snapshot not null");
        assertTrue(Long.valueOf(12345L).equals(s.ts), "ts matches");
        assertTrue(s.pc != null, "pc not null");
        assertTrue(Boolean.FALSE.equals(s.pc.online), "pc offline");
        assertTrue(s.pc.cpu == null, "cpu null");
        assertTrue(s.pc.ram == null, "ram null");
        assertTrue(s.pc.gpu == null, "gpu null");
        assertTrue(s.pc.net == null, "net null");
        assertTrue(s.pc.disks == null, "disks null");
        assertTrue(s.pc.uptimeSec == null, "uptimeSec null");
        assertTrue(s.phone == null, "phone null");

        MetricsSnapshot empty = MetricsParser.parse("{}");
        assertTrue(empty != null, "empty snapshot not null");
        assertTrue(empty.ts == null, "ts null");
        assertTrue(empty.pc == null, "pc null");
        assertTrue(empty.phone == null, "phone null");

        System.out.println("PASS: testMissingFields");
    }

    public static void testNullInputAndFields() {
        MetricsSnapshot sNull = MetricsParser.parse(null);
        assertTrue(sNull != null, "parse(null) must return non-null snapshot");
        assertTrue(sNull.ts == null && sNull.pc == null && sNull.phone == null, "all fields null on null input");

        String json = "{\"ts\": null, \"pc\": {\"online\": null, \"cpu\": {\"percent\": null}}, \"phone\": null}";
        MetricsSnapshot s = MetricsParser.parse(json);
        assertTrue(s != null, "snapshot not null");
        assertTrue(s.ts == null, "ts is null");
        assertTrue(s.pc != null, "pc not null");
        assertTrue(s.pc.online == null, "pc.online is null");
        assertTrue(s.pc.cpu != null, "cpu not null");
        assertTrue(s.pc.cpu.percent == null, "cpu.percent is null");
        assertTrue(s.phone == null, "phone is null");

        System.out.println("PASS: testNullInputAndFields");
    }

    public static void testWrongTypes() {
        String json = "{\n"
                + "  \"ts\": \"not_a_number\",\n"
                + "  \"pc\": \"not_an_object\",\n"
                + "  \"phone\": {\n"
                + "    \"battery\": 12345,\n"
                + "    \"tailscale\": \"not_a_bool\",\n"
                + "    \"uptimeSec\": \"invalid\"\n"
                + "  }\n"
                + "}";

        MetricsSnapshot s = MetricsParser.parse(json);
        assertTrue(s != null, "snapshot not null");
        assertTrue(s.ts == null, "ts should be null when given string");
        assertTrue(s.pc == null, "pc should be null when given string");
        assertTrue(s.phone != null, "phone is an object");
        assertTrue(s.phone.battery == null, "battery should be null when given number");
        assertTrue(s.phone.tailscale == null, "tailscale should be null when given string");
        assertTrue(s.phone.uptimeSec == null, "uptimeSec should be null when given invalid string");

        System.out.println("PASS: testWrongTypes");
    }

    public static void testTooDeep() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            sb.append("{\"layer\":");
        }
        sb.append("{\"ts\":123}");
        for (int i = 0; i < 30; i++) {
            sb.append("}");
        }

        MetricsSnapshot s = MetricsParser.parse(sb.toString());
        assertTrue(s != null, "snapshot not null on deep nesting");
        System.out.println("PASS: testTooDeep");
    }

    public static void testTooLarge() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"ts\":123,\"pad\":\"");
        for (int i = 0; i < 150000; i++) {
            sb.append("x");
        }
        sb.append("\"}");

        MetricsSnapshot s = MetricsParser.parse(sb.toString());
        assertTrue(s != null, "snapshot not null on oversized JSON");
        assertTrue(s.ts == null, "should reject oversized input without parsing");
        System.out.println("PASS: testTooLarge");
    }

    public static void testLongStrings() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"pc\":{\"online\":true,\"cpu\":{\"name\":\"");
        for (int i = 0; i < 5000; i++) {
            sb.append("A");
        }
        sb.append("\"}}}");

        MetricsSnapshot s = MetricsParser.parse(sb.toString());
        assertTrue(s != null && s.pc != null && s.pc.cpu != null, "parsed safely");
        assertTrue(s.pc.cpu.name != null, "name parsed");
        assertTrue(s.pc.cpu.name.length() <= 1024, "name capped at MAX_STRING_LEN: " + s.pc.cpu.name.length());
        System.out.println("PASS: testLongStrings");
    }
}
