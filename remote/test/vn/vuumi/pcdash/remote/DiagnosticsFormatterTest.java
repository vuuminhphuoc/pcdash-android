package vn.vuumi.pcdash.remote;

import vn.vuumi.pcdash.i18n.L10n;

public class DiagnosticsFormatterTest {

    public static void main(String[] args) {
        L10n.setOverride(L10n.VI); // the assertions below check the Vietnamese UI text
        System.out.println("Running DiagnosticsFormatterTest...");
        testFormatNullOrEmptyJson();
        testFormatFullDiagnostics();
        testFormatRelayDERPWhenDirectUnavailable();
        testFormatUdpBlockedAndCgnat();
        System.out.println("ALL DiagnosticsFormatterTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testFormatNullOrEmptyJson() {
        String res1 = DiagnosticsFormatter.format(null);
        assertTrue(res1 != null && res1.contains("Chưa có thông tin chẩn đoán"), "null json should return notice");

        String res2 = DiagnosticsFormatter.format("   ");
        assertTrue(res2 != null && res2.contains("Chưa có thông tin chẩn đoán"), "empty json should return notice");
        System.out.println("PASS: testFormatNullOrEmptyJson");
    }

    public static void testFormatFullDiagnostics() {
        String json = "{"
                + "\"backendState\":\"Running\","
                + "\"healthWarnings\":[\"Network interface MTU mismatch\"],"
                + "\"selfOnline\":true,"
                + "\"preferredDERP\":1,"
                + "\"derpLatency\":{\"1\":42,\"2\":180},"
                + "\"udp\":true,"
                + "\"netcheck\":{\"udp\":true,\"ipv4\":true,\"ipv6\":false,\"globalV4\":\"11.*.*.50:54321\",\"preferredDERP\":1,\"derpLatency\":{\"1\":42}},"
                + "\"peerHost\":{\"online\":true,\"curAddr\":\"192.168.1.50:41641\",\"relay\":\"1\",\"lastHandshake\":\"2026-10-20T09:46:22Z\",\"rxBytes\":1000,\"txBytes\":2000},"
                + "\"lastDialError\":\"dial 100.64.0.1:8091: context deadline exceeded\","
                + "\"lastDialErrorTime\":\"2026-10-20T09:46:22Z\","
                + "\"interfaces\":["
                + "  {\"name\":\"wlan0\",\"up\":false,\"addrs\":[]},"
                + "  {\"name\":\"rmnet_data0\",\"up\":true,\"addrs\":[\"11.22.33.44/24\"]}"
                + "],"
                + "\"logs\":[\"Line 1\",\"Line 2\"]"
                + "}";

        String out = DiagnosticsFormatter.format(json);
        assertTrue(out.contains("Trạng thái tsnet: Running (Self: Online)"), "should contain tsnet status");
        assertTrue(out.contains("Đường đi tới PC: Trực tiếp (Direct: 192.168.1.50:41641)"), "should contain direct path");
        assertTrue(out.contains("Bắt tay: 2026-10-20T09:46:22Z"), "should contain last handshake");
        assertTrue(out.contains("DERP gần nhất: Vùng 1 (Độ trễ: 42 ms)"), "should contain preferred DERP and latency");
        assertTrue(out.contains("UDP STUN: Có thông [IP ngoài: 11.*.*.50:54321]"), "should contain UDP info");
        assertTrue(out.contains("dial 100.64.0.1:8091: context deadline exceeded (2026-10-20T09:46:22Z)"), "should contain dial error");
        assertTrue(out.contains("wlan0 [DOWN]"), "should contain wlan0 down");
        assertTrue(out.contains("rmnet_data0 [UP] [11.22.33.44/24]"), "should contain rmnet_data0 up");
        assertTrue(out.contains("Cảnh báo sức khỏe: Network interface MTU mismatch"), "should contain health warnings");
        System.out.println("PASS: testFormatFullDiagnostics");
    }

    public static void testFormatRelayDERPWhenDirectUnavailable() {
        String json = "{"
                + "\"backendState\":\"Running\","
                + "\"healthWarnings\":[],"
                + "\"selfOnline\":true,"
                + "\"preferredDERP\":3,"
                + "\"derpLatency\":{\"3\":85},"
                + "\"udp\":false,"
                + "\"peerHost\":{\"online\":true,\"relay\":\"3\",\"lastHandshake\":\"2026-10-20T10:00:00Z\",\"rxBytes\":500,\"txBytes\":600},"
                + "\"lastDialError\":\"\","
                + "\"lastDialErrorTime\":\"\","
                + "\"interfaces\":[{\"name\":\"rmnet_data1\",\"up\":true,\"addrs\":[\"11.55.66.77/24\"]}]"
                + "}";

        String out = DiagnosticsFormatter.format(json);
        assertTrue(out.contains("Đường đi tới PC: Tiếp sóng DERP (Vùng 3)"), "should detect DERP relay route");
        assertTrue(out.contains("UDP STUN: Không thông (bị chặn/CGNAT)"), "should detect UDP not working");
        assertTrue(out.contains("Lỗi dial cuối: Không có lỗi"), "should report no dial error");
        System.out.println("PASS: testFormatRelayDERPWhenDirectUnavailable");
    }

    public static void testFormatUdpBlockedAndCgnat() {
        String json = "{"
                + "\"backendState\":\"Starting\","
                + "\"healthWarnings\":[],"
                + "\"selfOnline\":false,"
                + "\"preferredDERP\":-1,"
                + "\"udp\":false,"
                + "\"lastDialError\":\"dial 100.64.0.1:8091: connection timed out\","
                + "\"lastDialErrorTime\":\"2026-10-20T10:05:00Z\","
                + "\"interfaces\":[]"
                + "}";

        String out = DiagnosticsFormatter.format(json);
        assertTrue(out.contains("Trạng thái tsnet: Starting (Self: Chưa kết nối)"), "should report starting status");
        assertTrue(out.contains("DERP gần nhất: Chưa xác định"), "should report undetermined DERP");
        assertTrue(out.contains("UDP STUN: Không thông (bị chặn/CGNAT)"), "should report blocked UDP");
        assertTrue(out.contains("dial 100.64.0.1:8091: connection timed out"), "should report timeout dial error");
        System.out.println("PASS: testFormatUdpBlockedAndCgnat");
    }
}
