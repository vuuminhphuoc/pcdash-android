package vn.vuumi.pcdash.remote;

public class HistoryParserTest {

    public static void main(String[] args) {
        System.out.println("Running HistoryParserTest...");
        testValidHistory();
        testEmptyHistory();
        testMismatchedArrayLengthThrows();
        testMissingRequiredArraysThrows();
        testInvalidIntervalThrows();
        testNonMonotonicTimestampsThrows();
        testInvalidFieldTypesThrows();
        testNullAndEmptyInputThrows();
        System.out.println("ALL HistoryParserTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testValidHistory() {
        String json = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [1729900000000, 1729900030000, 1729900060000],\n"
                + "  \"cpu\": [15.5, null, 25.0],\n"
                + "  \"gpu\": [40.2, 42.0, null],\n"
                + "  \"ram\": [60.0, 61.2, 62.5],\n"
                + "  \"down\": [102400, 204800, null],\n"
                + "  \"up\": [51200, 10240, 0],\n"
                + "  \"cpuTemp\": [55.0, null, 60.0],\n"
                + "  \"gpuTemp\": [48.0, 50.0, null],\n"
                + "  \"online\": [1, 0, 1]\n"
                + "}";
        HistoryParser.HistoryData data = HistoryParser.parse(json);
        assertTrue(data != null, "data not null");
        assertTrue(data.intervalMs == 30000L, "intervalMs matches");
        assertTrue(data.sampleCount == 3, "sample count is 3");
        assertTrue(!data.isEmpty(), "not empty");

        assertTrue(data.ts[0] == 1729900000000L, "ts[0]");
        assertTrue(data.ts[1] == 1729900030000L, "ts[1]");
        assertTrue(data.ts[2] == 1729900060000L, "ts[2]");

        assertTrue(Double.valueOf(15.5).equals(data.cpu[0]), "cpu[0]");
        assertTrue(data.cpu[1] == null, "cpu[1] null");
        assertTrue(Double.valueOf(25.0).equals(data.cpu[2]), "cpu[2]");

        assertTrue(Long.valueOf(102400L).equals(data.down[0]), "down[0]");
        assertTrue(data.down[2] == null, "down[2] null");

        assertTrue(data.online[0] == 1, "online[0]");
        assertTrue(data.online[1] == 0, "online[1]");
        assertTrue(data.online[2] == 1, "online[2]");

        System.out.println("PASS: testValidHistory");
    }

    public static void testEmptyHistory() {
        String json = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [],\n"
                + "  \"cpu\": [],\n"
                + "  \"gpu\": [],\n"
                + "  \"ram\": [],\n"
                + "  \"down\": [],\n"
                + "  \"up\": [],\n"
                + "  \"cpuTemp\": [],\n"
                + "  \"gpuTemp\": [],\n"
                + "  \"online\": []\n"
                + "}";
        HistoryParser.HistoryData data = HistoryParser.parse(json);
        assertTrue(data != null, "data not null");
        assertTrue(data.sampleCount == 0, "sample count 0");
        assertTrue(data.isEmpty(), "isEmpty true");
        System.out.println("PASS: testEmptyHistory");
    }

    public static void testMismatchedArrayLengthThrows() {
        // ts has 2 elements, but cpu has 1 element
        String json1 = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [1000, 2000],\n"
                + "  \"cpu\": [10.0],\n"
                + "  \"gpu\": [10.0, 20.0],\n"
                + "  \"ram\": [10.0, 20.0],\n"
                + "  \"down\": [100, 200],\n"
                + "  \"up\": [10, 20],\n"
                + "  \"cpuTemp\": [50.0, 52.0],\n"
                + "  \"gpuTemp\": [40.0, 42.0],\n"
                + "  \"online\": [1, 1]\n"
                + "}";
        assertParseThrows(json1, "mismatched cpu length");

        // online has 3 elements, others have 2
        String json2 = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [1000, 2000],\n"
                + "  \"cpu\": [10.0, 20.0],\n"
                + "  \"gpu\": [10.0, 20.0],\n"
                + "  \"ram\": [10.0, 20.0],\n"
                + "  \"down\": [100, 200],\n"
                + "  \"up\": [10, 20],\n"
                + "  \"cpuTemp\": [50.0, 52.0],\n"
                + "  \"gpuTemp\": [40.0, 42.0],\n"
                + "  \"online\": [1, 1, 0]\n"
                + "}";
        assertParseThrows(json2, "mismatched online length");
        System.out.println("PASS: testMismatchedArrayLengthThrows");
    }

    public static void testMissingRequiredArraysThrows() {
        // missing gpuTemp
        String json = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [1000],\n"
                + "  \"cpu\": [10.0],\n"
                + "  \"gpu\": [10.0],\n"
                + "  \"ram\": [10.0],\n"
                + "  \"down\": [100],\n"
                + "  \"up\": [10],\n"
                + "  \"cpuTemp\": [50.0],\n"
                + "  \"online\": [1]\n"
                + "}";
        assertParseThrows(json, "missing gpuTemp");
        System.out.println("PASS: testMissingRequiredArraysThrows");
    }

    public static void testInvalidIntervalThrows() {
        String jsonZero = "{\"intervalMs\": 0, \"ts\":[], \"cpu\":[], \"gpu\":[], \"ram\":[], \"down\":[], \"up\":[], \"cpuTemp\":[], \"gpuTemp\":[], \"online\":[]}";
        assertParseThrows(jsonZero, "zero intervalMs");

        String jsonNegative = "{\"intervalMs\": -100, \"ts\":[], \"cpu\":[], \"gpu\":[], \"ram\":[], \"down\":[], \"up\":[], \"cpuTemp\":[], \"gpuTemp\":[], \"online\":[]}";
        assertParseThrows(jsonNegative, "negative intervalMs");

        String jsonMissing = "{\"ts\":[], \"cpu\":[], \"gpu\":[], \"ram\":[], \"down\":[], \"up\":[], \"cpuTemp\":[], \"gpuTemp\":[], \"online\":[]}";
        assertParseThrows(jsonMissing, "missing intervalMs");
        System.out.println("PASS: testInvalidIntervalThrows");
    }

    public static void testNonMonotonicTimestampsThrows() {
        // ts goes backwards (2000 -> 1500)
        String json = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [2000, 1500],\n"
                + "  \"cpu\": [10.0, 20.0],\n"
                + "  \"gpu\": [10.0, 20.0],\n"
                + "  \"ram\": [10.0, 20.0],\n"
                + "  \"down\": [100, 200],\n"
                + "  \"up\": [10, 20],\n"
                + "  \"cpuTemp\": [50.0, 52.0],\n"
                + "  \"gpuTemp\": [40.0, 42.0],\n"
                + "  \"online\": [1, 1]\n"
                + "}";
        assertParseThrows(json, "decreasing ts");
        System.out.println("PASS: testNonMonotonicTimestampsThrows");
    }

    public static void testInvalidFieldTypesThrows() {
        // string inside cpu array
        String json = "{\n"
                + "  \"intervalMs\": 30000,\n"
                + "  \"ts\": [1000],\n"
                + "  \"cpu\": [\"high\"],\n"
                + "  \"gpu\": [10.0],\n"
                + "  \"ram\": [10.0],\n"
                + "  \"down\": [100],\n"
                + "  \"up\": [10],\n"
                + "  \"cpuTemp\": [50.0],\n"
                + "  \"gpuTemp\": [40.0],\n"
                + "  \"online\": [1]\n"
                + "}";
        assertParseThrows(json, "string in cpu");
        System.out.println("PASS: testInvalidFieldTypesThrows");
    }

    public static void testNullAndEmptyInputThrows() {
        assertParseThrows(null, "null input");
        assertParseThrows("   ", "whitespace input");
        assertTrue(HistoryParser.optParse(null) == null, "optParse null");
        assertTrue(HistoryParser.optParse("bad") == null, "optParse bad");
        System.out.println("PASS: testNullAndEmptyInputThrows");
    }

    private static void assertParseThrows(String json, String reason) {
        try {
            HistoryParser.parse(json);
            throw new AssertionError("Expected IllegalArgumentException (" + reason + ") but succeeded");
        } catch (IllegalArgumentException expected) {
            // pass
        }
    }
}
