package vn.vuumi.pcdash.remote;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MetricsApiTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Running MetricsApiTest...");
        testSuccessfulGetMetrics();
        testSuccessfulGetLogAndHistory();
        test401ThrowsDeviceRevokedException();
        test404ThrowsUnsupportedRouteException();
        System.out.println("ALL MetricsApiTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testSuccessfulGetMetrics() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();
        final String mockJson = "{\"ts\":1792480000000,\"pc\":{\"online\":true}}";
        final boolean[] sawExpectedAuth = new boolean[1];
        final boolean[] sawExpectedPath = new boolean[1];

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[2048];
                    int read = in.read(buf);
                    String req = new String(buf, 0, read, StandardCharsets.UTF_8);

                    if (req.contains("GET /v1/metrics")) {
                        sawExpectedPath[0] = true;
                    }
                    if (req.contains("Authorization: Bearer my_secret_token")) {
                        sawExpectedAuth[0] = true;
                    }

                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + mockJson.length() + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + mockJson;
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        RemoteApiClient client = new RemoteApiClient();
        String result = client.getMetrics(port, "my_secret_token");
        server.close();

        assertTrue(sawExpectedPath[0], "request must hit GET /v1/metrics");
        assertTrue(sawExpectedAuth[0], "request must send Bearer authorization header");
        assertTrue(mockJson.equals(result), "returned JSON matches server response");

        System.out.println("PASS: testSuccessfulGetMetrics");
    }

    public static void test401ThrowsDeviceRevokedException() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[1024];
                    int read = in.read(buf);

                    String body = "{\"error\":\"revoked\"}";
                    String resp = "HTTP/1.1 401 Unauthorized\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length() + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + body;
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        RemoteApiClient client = new RemoteApiClient();
        boolean threwRevoked = false;
        try {
            client.getMetrics(port, "revoked_token");
        } catch (RemoteApiClient.DeviceRevokedException e) {
            threwRevoked = true;
        } finally {
            server.close();
        }

        assertTrue(threwRevoked, "HTTP 401 must throw DeviceRevokedException");
        System.out.println("PASS: test401ThrowsDeviceRevokedException");
    }
    public static void testSuccessfulGetLogAndHistory() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();
        final boolean[] sawLog = new boolean[1];

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[2048];
                    int r = in.read(buf);
                    String req = new String(buf, 0, r, StandardCharsets.UTF_8);
                    if (req.contains("GET /v1/log?limit=50") && req.contains("Authorization: Bearer test_token")) {
                        sawLog[0] = true;
                    }
                    String body = "{\"entries\":[]}";
                    String resp = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length() + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + body;
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        RemoteApiClient client = new RemoteApiClient();
        String logRes = client.getLog(port, "test_token", 50);
        server.close();
        assertTrue(sawLog[0], "hits GET /v1/log?limit=50 with bearer token");
        assertTrue("{\"entries\":[]}".equals(logRes), "log response matches");
        System.out.println("PASS: testSuccessfulGetLogAndHistory");
    }

    public static void test404ThrowsUnsupportedRouteException() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    byte[] buf = new byte[1024];
                    int r = in.read(buf);
                    String body = "{\"error\":\"not found\"}";
                    String resp = "HTTP/1.1 404 Not Found\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length() + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + body;
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        RemoteApiClient client = new RemoteApiClient();
        boolean threw404 = false;
        try {
            client.getHistory(port, "token", 60);
        } catch (RemoteApiClient.UnsupportedRouteException e) {
            threw404 = true;
        } finally {
            server.close();
        }

        assertTrue(threw404, "HTTP 404 must throw UnsupportedRouteException");
        System.out.println("PASS: test404ThrowsUnsupportedRouteException");
    }
}
