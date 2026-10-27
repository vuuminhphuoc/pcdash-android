package vn.vuumi.pcdash;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicReference;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public class PcQuotaClientTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null && expected == null) return;
        if (actual == null || !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    public static void testRefreshAddsQuery() {
        final AtomicReference<String> requestedUrl = new AtomicReference<String>();
        PcQuotaClient client = new PcQuotaClient("http://127.0.0.1:8799/quota", TimeSource.SYSTEM) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                requestedUrl.set(url);
                return "{\"providers\":[]}";
            }
        };

        // refresh = false: no ?refresh=1
        String r1 = client.getQuotaJson(false);
        assertEquals(r1, "{\"providers\":[]}", "fetch should succeed without refresh");
        assertEquals(requestedUrl.get(), "http://127.0.0.1:8799/quota", "URL should not have refresh query");

        // refresh = true: appends ?refresh=1
        String r2 = client.getQuotaJson(true);
        assertEquals(r2, "{\"providers\":[]}", "fetch should succeed with refresh");
        assertEquals(requestedUrl.get(), "http://127.0.0.1:8799/quota?refresh=1", "URL must include ?refresh=1");

        // if URL already has query parameters, append &refresh=1
        PcQuotaClient clientWithQuery = new PcQuotaClient("http://127.0.0.1:8799/quota?existing=param", TimeSource.SYSTEM) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                requestedUrl.set(url);
                return "{\"providers\":[]}";
            }
        };
        clientWithQuery.getQuotaJson(true);
        assertEquals(requestedUrl.get(), "http://127.0.0.1:8799/quota?existing=param&refresh=1", "URL with existing query must use &refresh=1");
    }

    public static void testBodyUnder256KbAccepted() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\":[],\"data\":\"");
        for (int i = 0; i < 100 * 1024; i++) {
            sb.append('x');
        }
        sb.append("\"}");
        final String payload100Kb = sb.toString();
        assertTrue(payload100Kb.getBytes(StandardCharsets.UTF_8).length >= 100 * 1024, "Payload must be at least 100 KB");
        assertTrue(payload100Kb.getBytes(StandardCharsets.UTF_8).length < PcQuotaClient.MAX_RESPONSE_BYTES, "Payload must be under 256 KB");

        PcQuotaClient client = new PcQuotaClient("http://127.0.0.1:8799/quota", TimeSource.SYSTEM) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                return payload100Kb;
            }
        };

        String res = client.getQuotaJson(false);
        assertTrue(res != null, "100 KB payload under 256 KB limit must be accepted");
        assertEquals(res, payload100Kb, "Returned payload must match");
    }

    public static void testOver256KbRejected() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"data\":\"");
        for (int i = 0; i < 260 * 1024; i++) {
            sb.append('y');
        }
        sb.append("\"}");
        final String payload260Kb = sb.toString();

        // 1. If fetchRaw returns > 256 KB, getQuotaJson rejects it
        PcQuotaClient clientOver = new PcQuotaClient("http://127.0.0.1:8799/quota", TimeSource.SYSTEM) {
            @Override
            protected String fetchRaw(String url, int timeoutMs) throws IOException {
                return payload260Kb;
            }
        };
        String res = clientOver.getQuotaJson(false);
        assertEquals(res, null, "Payload over 256 KB must be rejected (return null)");

        // 2. Real fetchRaw over HTTP with oversized body is rejected
        final ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    InputStream is = s.getInputStream();
                    byte[] b = new byte[512];
                    is.read(b);
                    OutputStream os = s.getOutputStream();
                    byte[] bodyBytes = payload260Kb.getBytes(StandardCharsets.UTF_8);
                    String header = "HTTP/1.1 200 OK\r\nContent-Length: " + bodyBytes.length + "\r\nConnection: close\r\n\r\n";
                    os.write(header.getBytes(StandardCharsets.UTF_8));
                    os.write(bodyBytes);
                    os.flush();
                    s.close();
                } catch (Exception ignored) {
                } finally {
                    try { server.close(); } catch (Exception ignored) {}
                }
            }
        });
        t.start();

        try {
            PcQuotaClient realClient = new PcQuotaClient("http://127.0.0.1:" + port + "/quota", TimeSource.SYSTEM);
            String rawOver = realClient.getQuotaJson(false);
            assertEquals(rawOver, null, "Real fetchRaw exceeding 256 KB must return null");
        } finally {
            t.join(3000);
        }
    }

    public static void testNon200ReturnsNull() throws Exception {
        final ServerSocket server = new ServerSocket(0);
        int port = server.getLocalPort();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    InputStream is = s.getInputStream();
                    byte[] b = new byte[512];
                    is.read(b);
                    OutputStream os = s.getOutputStream();
                    String response = "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 15\r\nConnection: close\r\n\r\n{\"error\":\"fail\"}";
                    os.write(response.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    s.close();
                } catch (Exception ignored) {
                } finally {
                    try { server.close(); } catch (Exception ignored) {}
                }
            }
        });
        t.start();

        try {
            PcQuotaClient realClient = new PcQuotaClient("http://127.0.0.1:" + port + "/quota", TimeSource.SYSTEM);
            String result = realClient.getQuotaJson(false);
            assertEquals(result, null, "Non-200 HTTP response must return null");
        } finally {
            t.join(3000);
        }
    }

    public static void testPcConnNotConfigured() {
        PcConn.configure(null);
        assertEquals(PcConn.isConfigured(), false, "isConfigured should be false when null");
        assertEquals(PcConn.statusLine(), "Chưa cấu hình PC", "statusLine should indicate unconfigured");
        assertEquals(PcConn.statusJson(), "{\"online\":false,\"port\":null,\"rtt_ms\":0,\"configured\":false}", "statusJson should indicate unconfigured");
        String act = PcConn.doAction("wake");
        assertTrue(act != null && act.startsWith("Lỗi:"), "doAction should return error starting with Lỗi:");
    }

    public static void testPcConnAgentExecSendsTokenAndHandles403And503() throws Exception {
        final ServerSocket server = new ServerSocket(0);
        final int port = server.getLocalPort();
        final String token = "test-agent-token-999";
        final AtomicReference<String> receivedToken = new AtomicReference<String>();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s1 = server.accept();
                    BufferedReader r1 = new BufferedReader(new InputStreamReader(s1.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r1.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("x-pcagent-token:")) {
                            receivedToken.set(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    OutputStream os1 = s1.getOutputStream();
                    os1.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 21\r\nConnection: close\r\n\r\n{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
                    os1.flush();
                    s1.close();

                    Socket s2 = server.accept();
                    BufferedReader r2 = new BufferedReader(new InputStreamReader(s2.getInputStream(), StandardCharsets.UTF_8));
                    while ((line = r2.readLine()) != null && !line.isEmpty()) {}
                    OutputStream os2 = s2.getOutputStream();
                    os2.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 32\r\nConnection: close\r\n\r\n{\"error\":\"token_not_configured\"}".getBytes(StandardCharsets.UTF_8));
                    os2.flush();
                    s2.close();
                } catch (Exception ignored) {
                } finally {
                    try { server.close(); } catch (Exception ignored) {}
                }
            }
        });
        t.start();

        try {
            PcSettingsSource fakeSettings = new PcSettingsSource() {
                @Override public String getPcIp() { return "127.0.0.1"; }
                @Override public String getPcMac() { return "00:11:22:33:44:55"; }
                @Override public int getAgentPort() { return port; }
                @Override public String getAgentToken() { return token; }
                @Override public String getWebToken() { return "web-tok"; }
                @Override public boolean isConfigured() { return true; }
            };
            PcConn.configure(fakeSettings);

            String r403 = PcConn.doAction("sleep");
            assertEquals(r403, "Agent từ chối: sai mã token", "403 must surface as Vietnamese forbidden message");
            assertEquals(receivedToken.get(), token, "X-PcAgent-Token header must be sent");

            String r503 = PcConn.doAction("sleep");
            assertEquals(r503, "Agent PC chưa cấu hình token", "503 must surface as Vietnamese unconfigured message");
        } finally {
            t.join(3000);
            PcConn.configure(null);
        }
    }

    public static void testPcQuotaClientSendsTokenAndHandles403And503() throws Exception {
        final ServerSocket server = new ServerSocket(0);
        final int port = server.getLocalPort();
        final String token = "test-quota-token-xyz";
        final AtomicReference<String> receivedToken = new AtomicReference<String>();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s1 = server.accept();
                    BufferedReader r1 = new BufferedReader(new InputStreamReader(s1.getInputStream(), StandardCharsets.UTF_8));
                    String line;
                    while ((line = r1.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("x-pcagent-token:")) {
                            receivedToken.set(line.substring(line.indexOf(':') + 1).trim());
                        }
                    }
                    OutputStream os1 = s1.getOutputStream();
                    os1.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 21\r\nConnection: close\r\n\r\n{\"error\":\"forbidden\"}".getBytes(StandardCharsets.UTF_8));
                    os1.flush();
                    s1.close();

                    Socket s2 = server.accept();
                    BufferedReader r2 = new BufferedReader(new InputStreamReader(s2.getInputStream(), StandardCharsets.UTF_8));
                    while ((line = r2.readLine()) != null && !line.isEmpty()) {}
                    OutputStream os2 = s2.getOutputStream();
                    os2.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 32\r\nConnection: close\r\n\r\n{\"error\":\"token_not_configured\"}".getBytes(StandardCharsets.UTF_8));
                    os2.flush();
                    s2.close();
                } catch (Exception ignored) {
                } finally {
                    try { server.close(); } catch (Exception ignored) {}
                }
            }
        });
        t.start();

        try {
            PcQuotaClient client = new PcQuotaClient("http://127.0.0.1:" + port + "/quota", token, TimeSource.SYSTEM);
            String r403 = client.getQuotaJson(false);
            assertTrue(r403 != null && r403.contains("Agent từ chối: sai mã token"), "403 must return error JSON");
            assertEquals(client.getLastError(), "Agent từ chối: sai mã token", "403 lastError message");
            assertEquals(receivedToken.get(), token, "X-PcAgent-Token must match");

            String r503 = client.getQuotaJson(false);
            assertTrue(r503 != null && r503.contains("Agent PC chưa cấu hình token"), "503 must return error JSON");
            assertEquals(client.getLastError(), "Agent PC chưa cấu hình token", "503 lastError message");
        } finally {
            t.join(3000);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running PcQuotaClientTest...");
        testRefreshAddsQuery();
        System.out.println("  PASS: testRefreshAddsQuery");
        testBodyUnder256KbAccepted();
        System.out.println("  PASS: testBodyUnder256KbAccepted");
        testOver256KbRejected();
        System.out.println("  PASS: testOver256KbRejected");
        testNon200ReturnsNull();
        System.out.println("  PASS: testNon200ReturnsNull");
        testPcConnNotConfigured();
        System.out.println("  PASS: testPcConnNotConfigured");
        testPcConnAgentExecSendsTokenAndHandles403And503();
        System.out.println("  PASS: testPcConnAgentExecSendsTokenAndHandles403And503");
        testPcQuotaClientSendsTokenAndHandles403And503();
        System.out.println("  PASS: testPcQuotaClientSendsTokenAndHandles403And503");
        System.out.println("ALL PcQuotaClientTest PASSED");
    }
}
