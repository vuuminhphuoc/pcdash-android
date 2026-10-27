package vn.vuumi.pcdash.remote;

public class PcListTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    public static void main(String[] args) {
        System.out.println("Running PcListTest...");
        PcList list = PcList.parse("{\"pcs\":["
                + "{\"id\":\"pc1\",\"name\":\"Desk\",\"online\":true,\"port\":8799,\"rtt_ms\":12,\"configured\":true},"
                + "{\"id\":\"pc9f3a1b\",\"name\":\"Gaming \\\"PC\\\"\",\"online\":false,\"port\":null,\"rtt_ms\":2004,\"configured\":true}]}");
        assertEquals(list.pcs.size(), 2, "two PCs");
        assertEquals(list.pcs.get(1).name, "Gaming \"PC\"", "escaped name");
        assertEquals(list.pick("pc9f3a1b").online, false, "pick by id");
        assertEquals(list.pick("gone").id, "pc1", "unknown id: the first");
        assertEquals(list.pick(null).id, "pc1", "no id: the first");
        RemoteApiClient.RemoteStatus st = list.pick("pc1").toStatus();
        assertTrue(st.online && st.port == 8799 && st.rttMs == 12, "status of the picked PC");
        assertEquals(list.pick("pc9f3a1b").port, null, "no port when off");

        assertTrue(PcList.parse("{\"pcs\":[]}").pick("x") == null, "empty list");
        assertTrue(PcList.parse("{\"pcs\":[{\"name\":\"no id\"}]}").pcs.isEmpty(), "entries need an id");
        assertTrue(PcList.parse("{}") == null, "no pcs array");
        assertTrue(PcList.parse(null) == null, "null");
        System.out.println("ALL PcListTest PASSED");
    }
}
