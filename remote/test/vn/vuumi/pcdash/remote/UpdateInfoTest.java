package vn.vuumi.pcdash.remote;

public class UpdateInfoTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Running UpdateInfoTest...");
        testValidAvailableFalse();
        testValidFullUpdateInfo();
        testValidWithAvailableTrue();
        testInvalidSha256();
        testInvalidSize();
        testInvalidTypes();
        testMissingFields();
        testUnexpectedFields();
        testNullOrEmptyOrMalformedJson();
        testVersionComparison();
        System.out.println("ALL UpdateInfoTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    private static void assertFalse(boolean cond, String msg) {
        if (cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testValidAvailableFalse() {
        String json = "{\"available\":false}";
        UpdateInfo info = UpdateInfo.parse(json);
        assertFalse(info.available, "available must be false");
        System.out.println("  PASS: testValidAvailableFalse");
    }

    public static void testValidFullUpdateInfo() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        String json = "{\"versionCode\":2,\"versionName\":\"1.1\",\"sha256\":\"" + sha + "\",\"size\":16319163}";
        UpdateInfo info = UpdateInfo.parse(json);
        assertTrue(info.available, "available must be true");
        assertTrue(info.versionCode == 2, "versionCode must be 2");
        assertTrue("1.1".equals(info.versionName), "versionName must be 1.1");
        assertTrue(sha.equals(info.sha256), "sha256 must match");
        assertTrue(info.size == 16319163L, "size must be 16319163");
        System.out.println("  PASS: testValidFullUpdateInfo");
    }

    public static void testValidWithAvailableTrue() {
        String sha = "ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
        String json = "{\"available\":true,\"versionCode\":3,\"versionName\":\"2.0-beta\",\"sha256\":\"" + sha + "\",\"size\":1024}";
        UpdateInfo info = UpdateInfo.parse(json);
        assertTrue(info.available, "available must be true");
        assertTrue(info.versionCode == 3, "versionCode must be 3");
        assertTrue("2.0-beta".equals(info.versionName), "versionName must match");
        assertTrue(sha.toLowerCase().equals(info.sha256), "sha256 must be normalized to lowercase");
        assertTrue(info.size == 1024L, "size must match");
        System.out.println("  PASS: testValidWithAvailableTrue");
    }

    public static void testInvalidSha256() {
        String valid64 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // Too short (63 chars)
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + valid64.substring(0, 63) + "\",\"size\":100}");
            throw new AssertionError("Should reject 63-char sha256");
        } catch (IllegalArgumentException expected) {}

        // Too long (65 chars)
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + valid64 + "a\",\"size\":100}");
            throw new AssertionError("Should reject 65-char sha256");
        } catch (IllegalArgumentException expected) {}

        // Non-hex character
        String badHex = "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + badHex + "\",\"size\":100}");
            throw new AssertionError("Should reject non-hex sha256");
        } catch (IllegalArgumentException expected) {}

        System.out.println("  PASS: testInvalidSha256");
    }

    public static void testInvalidSize() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // Size 0
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":0}");
            throw new AssertionError("Should reject size 0");
        } catch (IllegalArgumentException expected) {}

        // Size negative
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":-10}");
            throw new AssertionError("Should reject negative size");
        } catch (IllegalArgumentException expected) {}

        // Size > 64 MB
        long tooBig = 64L * 1024 * 1024 + 1;
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":" + tooBig + "}");
            throw new AssertionError("Should reject size > 64 MB");
        } catch (IllegalArgumentException expected) {}

        System.out.println("  PASS: testInvalidSize");
    }

    public static void testInvalidTypes() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // versionCode is String
        try {
            UpdateInfo.parse("{\"versionCode\":\"2\",\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":100}");
            throw new AssertionError("Should reject string versionCode");
        } catch (IllegalArgumentException expected) {}

        // size is String
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":\"100\"}");
            throw new AssertionError("Should reject string size");
        } catch (IllegalArgumentException expected) {}

        // sha256 is number
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":123456,\"size\":100}");
            throw new AssertionError("Should reject numeric sha256");
        } catch (IllegalArgumentException expected) {}

        // available is String
        try {
            UpdateInfo.parse("{\"available\":\"false\"}");
            throw new AssertionError("Should reject string available");
        } catch (IllegalArgumentException expected) {}

        // versionName is number
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":1.0,\"sha256\":\"" + sha + "\",\"size\":100}");
            throw new AssertionError("Should reject numeric versionName");
        } catch (IllegalArgumentException expected) {}

        System.out.println("  PASS: testInvalidTypes");
    }

    public static void testMissingFields() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // Missing versionCode
        try {
            UpdateInfo.parse("{\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":100}");
            throw new AssertionError("Should reject missing versionCode");
        } catch (IllegalArgumentException expected) {}

        // Missing versionName
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"sha256\":\"" + sha + "\",\"size\":100}");
            throw new AssertionError("Should reject missing versionName");
        } catch (IllegalArgumentException expected) {}

        // Missing sha256
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"size\":100}");
            throw new AssertionError("Should reject missing sha256");
        } catch (IllegalArgumentException expected) {}

        // Missing size
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\"}");
            throw new AssertionError("Should reject missing size");
        } catch (IllegalArgumentException expected) {}

        System.out.println("  PASS: testMissingFields");
    }

    public static void testUnexpectedFields() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // Extra field in available:false
        try {
            UpdateInfo.parse("{\"available\":false,\"extra\":\"val\"}");
            throw new AssertionError("Should reject extra field with available:false");
        } catch (IllegalArgumentException expected) {}

        // Extra field in update info
        try {
            UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.0\",\"sha256\":\"" + sha + "\",\"size\":100,\"extra\":123}");
            throw new AssertionError("Should reject unknown fields");
        } catch (IllegalArgumentException expected) {}

        System.out.println("  PASS: testUnexpectedFields");
    }

    public static void testNullOrEmptyOrMalformedJson() {
        try {
            UpdateInfo.parse(null);
            throw new AssertionError("Should reject null");
        } catch (IllegalArgumentException expected) {}

        try {
            UpdateInfo.parse("");
            throw new AssertionError("Should reject empty string");
        } catch (IllegalArgumentException expected) {}

        try {
            UpdateInfo.parse("not json");
            throw new AssertionError("Should reject non-json");
        } catch (IllegalArgumentException expected) {}

        try {
            UpdateInfo.parse("[]");
            throw new AssertionError("Should reject array");
        } catch (IllegalArgumentException expected) {}

        System.out.println("  PASS: testNullOrEmptyOrMalformedJson");
    }

    public static void testVersionComparison() {
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        UpdateInfo hostV2 = UpdateInfo.parse("{\"versionCode\":2,\"versionName\":\"1.1\",\"sha256\":\"" + sha + "\",\"size\":100}");
        UpdateInfo unavailable = UpdateInfo.parse("{\"available\":false}");

        // Host > installed (host 2 > installed 1) -> true
        assertTrue(UpdateChecker.isUpdateAvailable(hostV2, 1), "host 2 > installed 1 must be true");

        // Host == installed (host 2 == installed 2) -> false
        assertFalse(UpdateChecker.isUpdateAvailable(hostV2, 2), "host 2 == installed 2 must be false");

        // Host < installed (host 2 < installed 3) -> false
        assertFalse(UpdateChecker.isUpdateAvailable(hostV2, 3), "host 2 < installed 3 must be false");

        // Unavailable -> false
        assertFalse(UpdateChecker.isUpdateAvailable(unavailable, 1), "unavailable update must be false");

        // Null -> false
        assertFalse(UpdateChecker.isUpdateAvailable(null, 1), "null update must be false");

        System.out.println("  PASS: testVersionComparison");
    }
}
