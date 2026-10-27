package vn.vuumi.pcdash;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class HostUpdateTest {

    static class FakeExecutor implements RemoteActionExecutor {
        @Override
        public String statusJson() {
            return "{\"online\":true,\"port\":8799,\"rtt_ms\":12}";
        }

        @Override
        public String doAction(String action) {
            return "ok: " + action;
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("Assertion failed: " + message);
        }
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    static class HttpResponse {
        final int code;
        final String contentType;
        final long contentLength;
        final String body;

        HttpResponse(int code, String contentType, long contentLength, String body) {
            this.code = code;
            this.contentType = contentType;
            this.contentLength = contentLength;
            this.body = body;
        }
    }

    private static HttpResponse sendRequest(int port, String method, String path, String authHeader) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        if (authHeader != null) {
            conn.setRequestProperty("Authorization", authHeader);
        }
        int code = conn.getResponseCode();
        String contentType = conn.getContentType();
        long contentLength = conn.getContentLengthLong();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        String body = "";
        if (is != null) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            body = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            is.close();
        }
        conn.disconnect();
        return new HttpResponse(code, contentType, contentLength, body);
    }

    public static void testInfoWithoutAssetReturnsAvailableFalse() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        FakeExecutor executor = new FakeExecutor();
        // Server with null update source
        RemoteApiServer server = new RemoteApiServer(store, executor, (UpdateAssetSource) null);
        server.start(0);
        int port = server.getPort();

        try {
            HttpResponse res = sendRequest(port, "GET", "/v1/update/info", "Bearer " + rawToken);
            assertEquals(res.code, 200, "status code when asset source is null");
            assertTrue(res.body.contains("\"available\":false"), "body must contain available:false");
        } finally {
            server.close();
        }

        // Server with FakeUpdateAssetSource returning null for openInfo
        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource(null, 0);
        RemoteApiServer server2 = new RemoteApiServer(store, executor, fakeSource);
        server2.start(0);
        int port2 = server2.getPort();
        try {
            HttpResponse res = sendRequest(port2, "GET", "/v1/update/info", "Bearer " + rawToken);
            assertEquals(res.code, 200, "status code when openInfo is null");
            assertTrue(res.body.contains("\"available\":false"), "body must contain available:false");
        } finally {
            server2.close();
        }
    }

    public static void testInfoWithAssetReturnsExactUpdateJson() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        String expectedJson = "{\"versionCode\":2,\"versionName\":\"1.1\",\"sha256\":\"abcdef0123456789\",\"size\":3145728}";
        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource(expectedJson, 3145728);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, fakeSource);
        server.start(0);
        int port = server.getPort();

        try {
            HttpResponse res = sendRequest(port, "GET", "/v1/update/info", "Bearer " + rawToken);
            assertEquals(res.code, 200, "status code when update.json present");
            assertEquals(res.body, expectedJson, "exact update.json body");
            assertTrue(res.contentType != null && res.contentType.contains("application/json"), "content-type must be application/json");
        } finally {
            server.close();
        }
    }

    public static void testUnauthorizedRequestsForBothRoutes() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource("{\"versionCode\":2}", 1024);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, fakeSource);
        server.start(0);
        int port = server.getPort();

        try {
            // /v1/update/info missing token
            HttpResponse r1 = sendRequest(port, "GET", "/v1/update/info", null);
            assertEquals(r1.code, 401, "missing token on /v1/update/info");
            assertTrue(r1.body.contains("unauthorized"), "must return unauthorized");

            // /v1/update/info wrong token
            HttpResponse r2 = sendRequest(port, "GET", "/v1/update/info", "Bearer wrong-token-value");
            assertEquals(r2.code, 401, "wrong token on /v1/update/info");
            assertTrue(r2.body.contains("unauthorized"), "must return unauthorized");

            // /v1/update/apk missing token
            HttpResponse r3 = sendRequest(port, "GET", "/v1/update/apk", null);
            assertEquals(r3.code, 401, "missing token on /v1/update/apk");
            assertTrue(r3.body.contains("unauthorized"), "must return unauthorized");

            // /v1/update/apk wrong token
            HttpResponse r4 = sendRequest(port, "GET", "/v1/update/apk", "Bearer wrong-token-value");
            assertEquals(r4.code, 401, "wrong token on /v1/update/apk");
            assertTrue(r4.body.contains("unauthorized"), "must return unauthorized");
        } finally {
            server.close();
        }
    }

    public static void testApkStreamingContentAndLengthWithSeveralMbSource() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        long threeMb = 3 * 1024 * 1024; // 3,145,728 bytes
        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource("{\"versionCode\":2}", threeMb);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, fakeSource);
        server.start(0);
        int port = server.getPort();

        try {
            URL url = new URL("http://127.0.0.1:" + port + "/v1/update/apk");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Bearer " + rawToken);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(15000);

            int code = conn.getResponseCode();
            assertEquals(code, 200, "status code for APK download");
            assertEquals(conn.getContentType(), "application/vnd.android.package-archive", "Content-Type header");
            assertEquals(conn.getContentLengthLong(), threeMb, "Content-Length header");

            InputStream is = conn.getInputStream();
            byte[] buf = new byte[16384];
            long totalRead = 0;
            boolean patternValid = true;
            int n;
            while ((n = is.read(buf)) != -1) {
                for (int i = 0; i < n; i++) {
                    byte expected = (byte) ((totalRead + i) & 0xFF);
                    if (buf[i] != expected) {
                        patternValid = false;
                        break;
                    }
                }
                totalRead += n;
            }
            is.close();
            conn.disconnect();

            assertEquals(totalRead, threeMb, "total bytes streamed");
            assertTrue(patternValid, "data stream byte pattern must match generator");
        } finally {
            server.close();
        }
    }

    public static void testSecondConcurrentDownloadGets503() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        long size = 1024 * 1024; // 1 MB
        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource("{\"versionCode\":2}", size);
        fakeSource.enablePauseOnRead();

        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, fakeSource);
        server.start(0);
        int port = server.getPort();

        try {
            final AtomicInteger client1Code = new AtomicInteger(0);
            final AtomicReference<Throwable> client1Error = new AtomicReference<Throwable>();

            Thread t1 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL("http://127.0.0.1:" + port + "/v1/update/apk");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Authorization", "Bearer " + rawToken);
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(15000);

                        int code = conn.getResponseCode();
                        client1Code.set(code);
                        InputStream is = conn.getInputStream();
                        byte[] buf = new byte[16384];
                        while (is.read(buf) != -1) {}
                        is.close();
                        conn.disconnect();
                    } catch (Throwable t) {
                        client1Error.set(t);
                    }
                }
            });
            t1.start();

            // Wait for client 1 to hit the pause latch while streaming
            assertTrue(fakeSource.waitForReadStarted(5000), "client 1 must start streaming");

            // Client 2 attempts concurrent download -> must receive 503
            HttpResponse r2 = sendRequest(port, "GET", "/v1/update/apk", "Bearer " + rawToken);
            assertEquals(r2.code, 503, "second concurrent download must receive 503");
            assertTrue(r2.body.contains("download_in_progress"), "503 body indicates in_progress");

            // Unpause client 1 and let it finish
            fakeSource.resumeRead();
            t1.join(5000);

            if (client1Error.get() != null) {
                throw new AssertionError("client 1 failed", client1Error.get());
            }
            assertEquals(client1Code.get(), 200, "client 1 succeeded with 200");

            // After client 1 finishes, client 3 can now download
            FakeUpdateAssetSource unpausedSource = new FakeUpdateAssetSource("{\"versionCode\":2}", 65536);
            // Replace or request again on server (lock was released)
            HttpResponse r3 = sendRequest(port, "GET", "/v1/update/apk", "Bearer " + rawToken);
            assertEquals(r3.code, 200, "third download after completion succeeds with 200");
        } finally {
            fakeSource.resumeRead();
            server.close();
        }
    }

    public static void testOtherRouteWorksDuringApkDownload() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        long size = 1024 * 1024;
        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource("{\"versionCode\":2,\"versionName\":\"1.1\"}", size);
        fakeSource.enablePauseOnRead();

        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, fakeSource);
        server.start(0);
        int port = server.getPort();

        try {
            Thread t1 = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        URL url = new URL("http://127.0.0.1:" + port + "/v1/update/apk");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setRequestProperty("Authorization", "Bearer " + rawToken);
                        conn.setConnectTimeout(5000);
                        conn.setReadTimeout(15000);
                        int code = conn.getResponseCode();
                        InputStream is = conn.getInputStream();
                        byte[] buf = new byte[16384];
                        while (is.read(buf) != -1) {}
                        is.close();
                        conn.disconnect();
                    } catch (Throwable ignored) {}
                }
            });
            t1.start();

            assertTrue(fakeSource.waitForReadStarted(5000), "client 1 must start streaming");

            // While client 1 is downloading, other routes MUST work normally
            HttpResponse statusRes = sendRequest(port, "GET", "/v1/status", "Bearer " + rawToken);
            assertEquals(statusRes.code, 200, "/v1/status must work during APK download");
            assertTrue(statusRes.body.contains("\"online\":true"), "status response valid");

            HttpResponse infoRes = sendRequest(port, "GET", "/v1/update/info", "Bearer " + rawToken);
            assertEquals(infoRes.code, 200, "/v1/update/info must work during APK download");
            assertTrue(infoRes.body.contains("\"versionCode\":2"), "info response valid");

            fakeSource.resumeRead();
            t1.join(5000);
        } finally {
            fakeSource.resumeRead();
            server.close();
        }
    }

    public static void testClientDisconnectMidwayDoesNotHangThread() throws Exception {
        File temp = File.createTempFile("dev-store-upd-", ".json");
        temp.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(temp);
        String rawToken = "update-test-token-123456789012345";
        store.add("Test Device", rawToken, "node-1");

        long size = 5 * 1024 * 1024; // 5 MB
        FakeUpdateAssetSource fakeSource = new FakeUpdateAssetSource("{\"versionCode\":2}", size);
        FakeExecutor executor = new FakeExecutor();
        RemoteApiServer server = new RemoteApiServer(store, executor, fakeSource);
        server.start(0);
        int port = server.getPort();

        try {
            // Connect client via raw TCP Socket
            Socket socket = new Socket("127.0.0.1", port);
            OutputStream os = socket.getOutputStream();
            String req = "GET /v1/update/apk HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + port + "\r\n"
                    + "Authorization: Bearer " + rawToken + "\r\n"
                    + "Connection: close\r\n\r\n";
            os.write(req.getBytes(StandardCharsets.UTF_8));
            os.flush();

            InputStream is = socket.getInputStream();
            byte[] buf = new byte[16384];
            // Read headers + first 32 KB
            int readTotal = 0;
            while (readTotal < 32768) {
                int r = is.read(buf);
                if (r < 0) break;
                readTotal += r;
            }
            // Abruptly close client socket
            socket.close();

            // Give server thread a moment to detect close and clean up
            long waitStart = System.currentTimeMillis();
            boolean streamClosed = false;
            while (System.currentTimeMillis() - waitStart < 3000) {
                if (fakeSource.isStreamClosed()) {
                    streamClosed = true;
                    break;
                }
                Thread.sleep(50);
            }
            assertTrue(streamClosed, "server must close APK stream upon client disconnect");

            // Now immediately verify a new download can start (not blocked by 503)
            HttpResponse nextRes = sendRequest(port, "GET", "/v1/update/apk", "Bearer " + rawToken);
            assertEquals(nextRes.code, 200, "new download must succeed immediately after previous client disconnected");
        } finally {
            server.close();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running HostUpdateTest...");

        testInfoWithoutAssetReturnsAvailableFalse();
        System.out.println("  PASS: testInfoWithoutAssetReturnsAvailableFalse");

        testInfoWithAssetReturnsExactUpdateJson();
        System.out.println("  PASS: testInfoWithAssetReturnsExactUpdateJson");

        testUnauthorizedRequestsForBothRoutes();
        System.out.println("  PASS: testUnauthorizedRequestsForBothRoutes");

        testApkStreamingContentAndLengthWithSeveralMbSource();
        System.out.println("  PASS: testApkStreamingContentAndLengthWithSeveralMbSource");

        testSecondConcurrentDownloadGets503();
        System.out.println("  PASS: testSecondConcurrentDownloadGets503");

        testOtherRouteWorksDuringApkDownload();
        System.out.println("  PASS: testOtherRouteWorksDuringApkDownload");

        testClientDisconnectMidwayDoesNotHangThread();
        System.out.println("  PASS: testClientDisconnectMidwayDoesNotHangThread");

        System.out.println("ALL 7 HOST UPDATE TESTS PASSED");
    }
}
