package vn.vuumi.pcdash;

import java.util.List;

public class PcListTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static final String TOKEN_A = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String TOKEN_B = "bbbbbbbbbbbbbbbbbbbbbbbb";

    private static void testSinglePcSetupIsTheFirstPc() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        PcSettings s = new PcSettings(prefs, new FakeSecretCipher());
        assertTrue(s.listPcs().isEmpty(), "Nothing set up");
        assertEquals(s.findPc(null), null, "No default PC");
        s.save("192.168.1.10", "AA:BB:CC:DD:EE:FF", 8799, TOKEN_A); // how a single PC was always saved
        List<PcProfile> pcs = s.listPcs();
        assertEquals(pcs.size(), 1, "One PC");
        assertEquals(pcs.get(0).id, PcSettings.FIRST_PC_ID, "Its id");
        assertEquals(pcs.get(0).name, "PC", "Default name");
        assertEquals(pcs.get(0).token, TOKEN_A, "Token read back");
        assertEquals(s.findPc(null).ip, "192.168.1.10", "Default is the first PC");
        System.out.println("PASS: testSinglePcSetupIsTheFirstPc");
    }

    private static void testAddUpdateRemove() {
        FakeSharedPreferences prefs = new FakeSharedPreferences();
        PcSettings s = new PcSettings(prefs, new FakeSecretCipher());
        String first = s.addPc("Desk", "192.168.1.10", "AA:BB:CC:DD:EE:01", 8799, TOKEN_A);
        assertEquals(first, PcSettings.FIRST_PC_ID, "First PC uses the old keys");
        assertEquals(s.getPcIp(), "192.168.1.10", "Old getters still see it");
        String second = s.addPc("Gaming | PC", "192.168.1.11", "AA:BB:CC:DD:EE:02", 9000, TOKEN_B);
        assertTrue(!second.equals(first), "New id");

        // Another instance (the service) sees the change; the token is never stored in clear.
        PcSettings other = new PcSettings(prefs, new FakeSecretCipher());
        List<PcProfile> pcs = other.listPcs();
        assertEquals(pcs.size(), 2, "Two PCs");
        assertEquals(pcs.get(1).name, "Gaming | PC", "Separator in a name survives");
        assertEquals(pcs.get(1).port, 9000, "Port");
        assertEquals(pcs.get(1).token, TOKEN_B, "Token decrypted");
        assertTrue(!prefs.getString(PcSettings.KEY_EXTRA_PCS, "").contains(TOKEN_B), "Token encrypted at rest");

        PcSettingsSource live = other.source(second);
        s.updatePc(second, "Gaming", "192.168.1.12", "AA:BB:CC:DD:EE:02", 9001, TOKEN_B);
        assertEquals(live.getPcIp(), "192.168.1.12", "A live source follows edits");
        assertEquals(live.getAgentPort(), 9001, "Port follows");

        s.removePc(first);
        pcs = s.listPcs();
        assertEquals(pcs.size(), 1, "One left");
        assertEquals(pcs.get(0).id, second, "The next PC moved up and kept its id");
        assertEquals(s.getPcIp(), "192.168.1.12", "and now sits in the old keys");
        assertEquals(live.getPcIp(), "192.168.1.12", "Live source still finds it");
        assertEquals(s.findPc(first), null, "Removed");

        s.removePc(second);
        assertTrue(s.listPcs().isEmpty(), "All removed");
        assertTrue(!s.isConfigured(), "Nothing configured");
        System.out.println("PASS: testAddUpdateRemove");
    }

    private static void testLimit() {
        PcSettings s = new PcSettings(new FakeSharedPreferences(), new FakeSecretCipher());
        for (int i = 0; i < PcSettings.MAX_PCS; i++) {
            s.addPc("PC" + i, "192.168.1." + (20 + i), "", 8799, TOKEN_A);
        }
        boolean threw = false;
        try {
            s.addPc("one too many", "192.168.1.99", "", 8799, TOKEN_A);
        } catch (IllegalStateException e) {
            threw = true;
        }
        assertTrue(threw, "At most " + PcSettings.MAX_PCS);
        assertEquals(s.listPcs().size(), PcSettings.MAX_PCS, "Count");
        System.out.println("PASS: testLimit");
    }

    private static void testFleetEntryJson() {
        PcProfile pc = new PcProfile("pc1a2b3c", "Gaming \"PC\"", "192.168.1.10", "", 8799, TOKEN_A);
        String json = PcFleet.entryJson(pc, "{\"online\":true,\"port\":8799,\"rtt_ms\":12,\"configured\":true}");
        assertEquals(json, "{\"id\":\"pc1a2b3c\",\"name\":\"Gaming \\\"PC\\\"\",\"online\":true,\"port\":8799,\"rtt_ms\":12,\"configured\":true}",
                "id and escaped name merged in front of the status");
        assertEquals(PcFleet.entryJson(pc, null), "{\"id\":\"pc1a2b3c\",\"name\":\"Gaming \\\"PC\\\"\"}", "no status");
        assertEquals(PcFleet.jsonString("a\nb"), "\"a\\u000ab\"", "control characters escaped");
        System.out.println("PASS: testFleetEntryJson");
    }

    private static void testPcQueryParam() {
        assertEquals(RemoteApiServer.pcParam("/v1/status"), null, "no query: first PC");
        assertEquals(RemoteApiServer.pcParam("/v1/status?pc=pc1a2b3c"), "pc1a2b3c", "pc param");
        assertEquals(RemoteApiServer.pcParam("/v1/quota?refresh=1&pc=pc1"), "pc1", "among others");
        assertEquals(RemoteApiServer.pcParam("/v1/status?pc=../etc"), null, "odd ids are ignored");
        assertEquals(RemoteApiServer.pcParam("/v1/status?pc="), null, "empty");
        System.out.println("PASS: testPcQueryParam");
    }

    public static void main(String[] args) {
        System.out.println("Running PcListTest...");
        testFleetEntryJson();
        testPcQueryParam();
        testSinglePcSetupIsTheFirstPc();
        testAddUpdateRemove();
        testLimit();
        System.out.println("ALL PcListTest PASSED");
    }
}
