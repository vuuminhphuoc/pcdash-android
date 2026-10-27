package vn.vuumi.pcdash.remote;

public class LogParserTest {

    public static void main(String[] args) {
        System.out.println("Running LogParserTest...");
        testValidLog();
        testEmptyEntries();
        testNullAndEmptyInput();
        testNonObjectRoot();
        testMissingEntries();
        testInvalidEntryTypes();
        testExceedingLimits();
        System.out.println("ALL LogParserTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testValidLog() {
        String json = "{\n"
                + "  \"entries\": [\n"
                + "    {\"ts\": 1729900000000, \"device\": \"Pixel 7\", \"source\": \"remote\", \"action\": \"wake\", \"ok\": true},\n"
                + "    {\"ts\": 1729900050000, \"device\": \"web\", \"source\": \"web\", \"action\": \"shutdown\", \"ok\": false}\n"
                + "  ]\n"
                + "}";
        LogParser.LogResponse res = LogParser.parse(json);
        assertTrue(res != null, "res not null");
        assertTrue(res.entries.size() == 2, "entries count 2");

        LogParser.LogEntry e1 = res.entries.get(0);
        assertTrue(e1.ts == 1729900000000L, "e1 ts");
        assertTrue("Pixel 7".equals(e1.device), "e1 device");
        assertTrue("remote".equals(e1.source), "e1 source");
        assertTrue("wake".equals(e1.action), "e1 action");
        assertTrue(e1.ok, "e1 ok");

        LogParser.LogEntry e2 = res.entries.get(1);
        assertTrue(e2.ts == 1729900050000L, "e2 ts");
        assertTrue("web".equals(e2.device), "e2 device");
        assertTrue("web".equals(e2.source), "e2 source");
        assertTrue("shutdown".equals(e2.action), "e2 action");
        assertTrue(!e2.ok, "e2 not ok");

        System.out.println("PASS: testValidLog");
    }

    public static void testEmptyEntries() {
        String json = "{\"entries\": []}";
        LogParser.LogResponse res = LogParser.parse(json);
        assertTrue(res != null && res.entries.isEmpty(), "empty entries list");
        System.out.println("PASS: testEmptyEntries");
    }

    public static void testNullAndEmptyInput() {
        try {
            LogParser.parse(null);
            assertTrue(false, "null must throw");
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            LogParser.parse("   ");
            assertTrue(false, "empty string must throw");
        } catch (IllegalArgumentException e) {
            // expected
        }
        assertTrue(LogParser.optParse(null) == null, "optParse null");
        assertTrue(LogParser.optParse("") == null, "optParse empty");
        System.out.println("PASS: testNullAndEmptyInput");
    }

    public static void testNonObjectRoot() {
        try {
            LogParser.parse("[{\"ts\": 123}]");
            assertTrue(false, "array root must throw");
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            LogParser.parse("\"hello\"");
            assertTrue(false, "string root must throw");
        } catch (IllegalArgumentException e) {
            // expected
        }
        System.out.println("PASS: testNonObjectRoot");
    }

    public static void testMissingEntries() {
        try {
            LogParser.parse("{\"data\": 123}");
            assertTrue(false, "missing entries must throw");
        } catch (IllegalArgumentException e) {
            // expected
        }
        try {
            LogParser.parse("{\"entries\": \"not an array\"}");
            assertTrue(false, "non-array entries must throw");
        } catch (IllegalArgumentException e) {
            // expected
        }
        System.out.println("PASS: testMissingEntries");
    }

    public static void testInvalidEntryTypes() {
        // ts is string
        assertParseThrows("{\"entries\": [{\"ts\": \"123\", \"device\": \"A\", \"source\": \"remote\", \"action\": \"wake\", \"ok\": true}]}");
        // ts <= 0
        assertParseThrows("{\"entries\": [{\"ts\": -10, \"device\": \"A\", \"source\": \"remote\", \"action\": \"wake\", \"ok\": true}]}");
        // ok is string
        assertParseThrows("{\"entries\": [{\"ts\": 123, \"device\": \"A\", \"source\": \"remote\", \"action\": \"wake\", \"ok\": \"true\"}]}");
        // device is number
        assertParseThrows("{\"entries\": [{\"ts\": 123, \"device\": 123, \"source\": \"remote\", \"action\": \"wake\", \"ok\": true}]}");
        // source is null/missing
        assertParseThrows("{\"entries\": [{\"ts\": 123, \"device\": \"A\", \"source\": null, \"action\": \"wake\", \"ok\": true}]}");
        // entry is not an object
        assertParseThrows("{\"entries\": [\"raw string\"]}");
        System.out.println("PASS: testInvalidEntryTypes");
    }

    public static void testExceedingLimits() {
        // String too long
        StringBuilder longStr = new StringBuilder();
        for (int i = 0; i < 200; i++) longStr.append("A");
        assertParseThrows("{\"entries\": [{\"ts\": 123, \"device\": \"" + longStr.toString() + "\", \"source\": \"remote\", \"action\": \"wake\", \"ok\": true}]}");

        // Too many entries (> 500)
        StringBuilder sb = new StringBuilder("{\"entries\": [");
        for (int i = 0; i < 501; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"ts\": 123, \"device\": \"A\", \"source\": \"remote\", \"action\": \"wake\", \"ok\": true}");
        }
        sb.append("]}");
        assertParseThrows(sb.toString());
        System.out.println("PASS: testExceedingLimits");
    }

    private static void assertParseThrows(String json) {
        try {
            LogParser.parse(json);
            throw new AssertionError("Expected IllegalArgumentException but parse succeeded for: " + json);
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}
