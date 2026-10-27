package vn.vuumi.pcdash.remote;

import java.io.IOException;
import java.util.Collections;

public class QuotaParserTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Running QuotaParserTest...");
        testFullValidMultiProvider();
        testEmptyReportsOrProviders();
        testInvalidPooledRemainingPct();
        testInvalidAccountRemainingPct();
        testTooLargePayloadRejected();
        testLoadingAndErrorFields();
        testExtraFieldsIgnored();
        testProviderLimitExceeded();
        testGroupLimitExceeded();
        testWindowLimitExceeded();
        testAccountLimitExceeded();
        testInvalidJsonStructure();
        System.out.println("ALL QuotaParserTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testFullValidMultiProvider() throws Exception {
        String json = "{\n"
                + "  \"fetchedAt\": 1730000000000,\n"
                + "  \"stale\": false,\n"
                + "  \"providers\": [\n"
                + "    {\n"
                + "      \"id\": \"google-antigravity\",\n"
                + "      \"name\": \"Antigravity\",\n"
                + "      \"groups\": [\n"
                + "        {\n"
                + "          \"name\": \"Gemini\",\n"
                + "          \"windows\": [\n"
                + "            {\n"
                + "              \"id\": \"5h\",\n"
                + "              \"label\": \"5 giờ\",\n"
                + "              \"pooledRemainingPct\": 95,\n"
                + "              \"accountsWithQuota\": 6,\n"
                + "              \"accountsTotal\": 6,\n"
                + "              \"resetsAt\": 1730010000000,\n"
                + "              \"accounts\": [\n"
                + "                {\"label\": \"user@*\", \"remainingPct\": 73, \"resetsAt\": 1730010000000}\n"
                + "              ]\n"
                + "            },\n"
                + "            {\n"
                + "              \"id\": \"weekly\",\n"
                + "              \"label\": \"Tuần\",\n"
                + "              \"pooledRemainingPct\": 80,\n"
                + "              \"accountsWithQuota\": 6,\n"
                + "              \"accountsTotal\": 6,\n"
                + "              \"resetsAt\": 1730500000000,\n"
                + "              \"accounts\": []\n"
                + "            }\n"
                + "          ]\n"
                + "        }\n"
                + "      ]\n"
                + "    },\n"
                + "    {\n"
                + "      \"id\": \"openai-codex\",\n"
                + "      \"name\": \"Codex\",\n"
                + "      \"groups\": [\n"
                + "        {\n"
                + "          \"name\": \"Codex\",\n"
                + "          \"windows\": [\n"
                + "            {\n"
                + "              \"id\": \"30d\",\n"
                + "              \"label\": \"30 ngày\",\n"
                + "              \"pooledRemainingPct\": 45,\n"
                + "              \"accountsWithQuota\": 1,\n"
                + "              \"accountsTotal\": 1,\n"
                + "              \"resetsAt\": null,\n"
                + "              \"accounts\": [\n"
                + "                {\"label\": \"Tài khoản 1\", \"remainingPct\": 45, \"resetsAt\": null}\n"
                + "              ]\n"
                + "            }\n"
                + "          ]\n"
                + "        }\n"
                + "      ]\n"
                + "    }\n"
                + "  ]\n"
                + "}";

        QuotaSnapshot s = QuotaParser.parse(json);
        assertTrue(s != null, "snapshot not null");
        assertTrue(s.fetchedAt == 1730000000000L, "fetchedAt matches");
        assertTrue(!s.stale, "stale matches false");
        assertTrue(!s.loading, "loading matches false");
        assertTrue(s.error == null, "error is null");
        assertTrue(s.providers != null && s.providers.size() == 2, "2 providers");

        QuotaSnapshot.Provider p1 = s.providers.get(0);
        assertTrue("google-antigravity".equals(p1.id), "p1 id");
        assertTrue("Antigravity".equals(p1.name), "p1 name");
        assertTrue(p1.groups.size() == 1, "p1 has 1 group");

        QuotaSnapshot.Group g1 = p1.groups.get(0);
        assertTrue("Gemini".equals(g1.name), "g1 name");
        assertTrue(g1.windows.size() == 2, "g1 has 2 windows");

        QuotaSnapshot.Window w1 = g1.windows.get(0);
        assertTrue("5h".equals(w1.id), "w1 id");
        assertTrue("5 giờ".equals(w1.label), "w1 label");
        assertTrue(w1.pooledRemainingPct == 95, "w1 pooledRemainingPct");
        assertTrue(w1.accountsWithQuota == 6, "w1 accountsWithQuota");
        assertTrue(w1.accountsTotal == 6, "w1 accountsTotal");
        assertTrue(Long.valueOf(1730010000000L).equals(w1.resetsAt), "w1 resetsAt");
        assertTrue(w1.accounts.size() == 1, "w1 accounts size 1");

        QuotaSnapshot.Account a1 = w1.accounts.get(0);
        assertTrue("user@*".equals(a1.label), "a1 label");
        assertTrue(a1.remainingPct == 73, "a1 remainingPct");
        assertTrue(Long.valueOf(1730010000000L).equals(a1.resetsAt), "a1 resetsAt");

        QuotaSnapshot.Provider p2 = s.providers.get(1);
        assertTrue("openai-codex".equals(p2.id), "p2 id");
        QuotaSnapshot.Window w30d = p2.groups.get(0).windows.get(0);
        assertTrue("30d".equals(w30d.id), "w30d id");
        assertTrue(w30d.resetsAt == null, "w30d resetsAt null");
        System.out.println("PASS: testFullValidMultiProvider");
    }

    public static void testEmptyReportsOrProviders() throws Exception {
        String json1 = "{\"fetchedAt\": 1000, \"providers\": []}";
        QuotaSnapshot s1 = QuotaParser.parse(json1);
        assertTrue(s1 != null, "s1 not null");
        assertTrue(s1.providers.isEmpty(), "providers empty");

        String json2 = "{\"fetchedAt\": 1000, \"reports\": [], \"providers\": []}";
        QuotaSnapshot s2 = QuotaParser.parse(json2);
        assertTrue(s2 != null, "s2 not null");
        assertTrue(s2.providers.isEmpty(), "providers empty with reports");

        System.out.println("PASS: testEmptyReportsOrProviders");
    }

    public static void testInvalidPooledRemainingPct() {
        String jsonOver = "{\"providers\": [{\"id\": \"p\", \"name\": \"P\", \"groups\": [{\"name\": \"G\", \"windows\": [{\"id\": \"5h\", \"label\": \"5h\", \"pooledRemainingPct\": 101}]}]}]}";
        try {
            QuotaParser.parse(jsonOver);
            throw new AssertionError("Should have rejected pooledRemainingPct 101");
        } catch (IOException expected) {
            // PASS
        }

        String jsonUnder = "{\"providers\": [{\"id\": \"p\", \"name\": \"P\", \"groups\": [{\"name\": \"G\", \"windows\": [{\"id\": \"5h\", \"label\": \"5h\", \"pooledRemainingPct\": -1}]}]}]}";
        try {
            QuotaParser.parse(jsonUnder);
            throw new AssertionError("Should have rejected pooledRemainingPct -1");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testInvalidPooledRemainingPct");
    }

    public static void testInvalidAccountRemainingPct() {
        String jsonOver = "{\"providers\": [{\"id\": \"p\", \"name\": \"P\", \"groups\": [{\"name\": \"G\", \"windows\": [{\"id\": \"5h\", \"label\": \"5h\", \"pooledRemainingPct\": 50, \"accounts\": [{\"label\": \"acc\", \"remainingPct\": 101}]}]}]}]}";
        try {
            QuotaParser.parse(jsonOver);
            throw new AssertionError("Should have rejected account remainingPct 101");
        } catch (IOException expected) {
            // PASS
        }

        String jsonUnder = "{\"providers\": [{\"id\": \"p\", \"name\": \"P\", \"groups\": [{\"name\": \"G\", \"windows\": [{\"id\": \"5h\", \"label\": \"5h\", \"pooledRemainingPct\": 50, \"accounts\": [{\"label\": \"acc\", \"remainingPct\": -5}]}]}]}]}";
        try {
            QuotaParser.parse(jsonUnder);
            throw new AssertionError("Should have rejected account remainingPct -5");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testInvalidAccountRemainingPct");
    }

    public static void testTooLargePayloadRejected() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\": [");
        while (sb.length() < 300 * 1024) {
            sb.append("{\"id\":\"p\",\"name\":\"p\",\"groups\":[]},");
        }
        sb.append("{\"id\":\"end\",\"name\":\"end\",\"groups\":[]}]}");
        assertTrue(sb.length() > 256 * 1024, "Length > 256KB");

        try {
            QuotaParser.parse(sb.toString());
            throw new AssertionError("Should have rejected payload > 256KB");
        } catch (IOException expected) {
            // PASS
        }

        try {
            QuotaParser.parse(null);
            throw new AssertionError("Should have rejected null payload");
        } catch (IOException expected) {
            // PASS
        }

        try {
            QuotaParser.parse("   ");
            throw new AssertionError("Should have rejected empty payload");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testTooLargePayloadRejected");
    }

    public static void testLoadingAndErrorFields() throws Exception {
        String json = "{\"loading\": true, \"error\": \"omp_unavailable\", \"stale\": true, \"providers\": []}";
        QuotaSnapshot s = QuotaParser.parse(json);
        assertTrue(s.loading, "loading true");
        assertTrue(s.stale, "stale true");
        assertTrue("omp_unavailable".equals(s.error), "error is omp_unavailable");
        assertTrue(s.providers.isEmpty(), "providers empty");
        System.out.println("PASS: testLoadingAndErrorFields");
    }

    public static void testExtraFieldsIgnored() throws Exception {
        String json = "{\"extraRoot\": 999, \"fetchedAt\": 100, \"providers\": [{\"id\":\"p\",\"name\":\"P\",\"unknownProp\":\"ignoreMe\",\"groups\":[]}]}";
        QuotaSnapshot s = QuotaParser.parse(json);
        assertTrue(s.fetchedAt == 100L, "fetchedAt 100");
        assertTrue(s.providers.size() == 1, "1 provider");
        assertTrue("p".equals(s.providers.get(0).id), "id p");
        System.out.println("PASS: testExtraFieldsIgnored");
    }

    public static void testProviderLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\": [");
        for (int i = 0; i < 51; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\": \"p").append(i).append("\", \"name\": \"P\", \"groups\": []}");
        }
        sb.append("]}");

        try {
            QuotaParser.parse(sb.toString());
            throw new AssertionError("Should have rejected > 50 providers");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testProviderLimitExceeded");
    }

    public static void testGroupLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\": [{\"id\":\"p\",\"name\":\"P\",\"groups\": [");
        for (int i = 0; i < 51; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\": \"G").append(i).append("\", \"windows\": []}");
        }
        sb.append("]}]}");

        try {
            QuotaParser.parse(sb.toString());
            throw new AssertionError("Should have rejected > 50 groups");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testGroupLimitExceeded");
    }

    public static void testWindowLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\": [{\"id\":\"p\",\"name\":\"P\",\"groups\": [{\"name\":\"G\",\"windows\":[");
        for (int i = 0; i < 51; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"id\":\"w").append(i).append("\",\"label\":\"W\",\"pooledRemainingPct\":50,\"accounts\":[]}");
        }
        sb.append("]}]}]}");

        try {
            QuotaParser.parse(sb.toString());
            throw new AssertionError("Should have rejected > 50 windows");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testWindowLimitExceeded");
    }

    public static void testAccountLimitExceeded() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"providers\": [{\"id\":\"p\",\"name\":\"P\",\"groups\": [{\"name\":\"G\",\"windows\":[{\"id\":\"w\",\"label\":\"W\",\"pooledRemainingPct\":50,\"accounts\":[");
        for (int i = 0; i < 101; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":\"acc").append(i).append("\",\"remainingPct\":50}");
        }
        sb.append("]}]}]}]}");

        try {
            QuotaParser.parse(sb.toString());
            throw new AssertionError("Should have rejected > 100 accounts");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testAccountLimitExceeded");
    }

    public static void testInvalidJsonStructure() {
        try {
            QuotaParser.parse("[]");
            throw new AssertionError("Should reject non-object root");
        } catch (IOException expected) {
            // PASS
        }

        try {
            QuotaParser.parse("{\"providers\": \"not_an_array\"}");
            throw new AssertionError("Should reject non-array providers");
        } catch (IOException expected) {
            // PASS
        }
        System.out.println("PASS: testInvalidJsonStructure");
    }
}
