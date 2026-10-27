package vn.vuumi.pcdash;

import java.net.InetAddress;
import java.net.ServerSocket;

public class PcConnProbeTest {

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static int closedPort() throws Exception {
        ServerSocket s = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = s.getLocalPort();
        s.close();
        return port;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running PcConnProbeTest...");
        InetAddress lo = InetAddress.getByName("127.0.0.1");
        ServerSocket a = new ServerSocket(0, 50, lo);
        ServerSocket b = new ServerSocket(0, 50, lo);
        try {
            int closed = closedPort();
            assertEquals(PcConn.firstOpenPort("127.0.0.1", new int[]{closed, a.getLocalPort()}, 1500),
                    a.getLocalPort(), "Skips a closed port");
            assertEquals(PcConn.firstOpenPort("127.0.0.1", new int[]{b.getLocalPort(), a.getLocalPort()}, 1500),
                    b.getLocalPort(), "Two open ports: the first in the list wins");
            long t0 = System.currentTimeMillis();
            assertEquals(PcConn.firstOpenPort("127.0.0.1", new int[]{closed, closedPort(), closedPort()}, 1500),
                    -1, "All closed");
            assertTrue(System.currentTimeMillis() - t0 < 1500, "Refused ports answer at once, not after a timeout");
        } finally {
            a.close();
            b.close();
        }
        System.out.println("ALL PcConnProbeTest PASSED");
    }
}
