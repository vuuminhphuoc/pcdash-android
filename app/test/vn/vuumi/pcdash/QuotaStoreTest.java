package vn.vuumi.pcdash;

public class QuotaStoreTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null && expected == null) return;
        if (actual == null || !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    public static void testFreshIsMarkedNotStale() {
        QuotaStore store = new QuotaStore();
        String fresh = "{\"fetchedAt\":1729900000000,\"providers\":[{\"id\":\"test\",\"name\":\"Test\"}]}";
        String resolved = store.resolve(fresh);
        assertTrue(resolved != null, "resolved should not be null");
        assertTrue(resolved.contains("\"stale\":false"), "fresh data must have stale:false");
        assertTrue(resolved.contains("\"fetchedAt\":1729900000000"), "fresh data must preserve fields");
        assertTrue(resolved.contains("\"providers\""), "fresh data must preserve providers");
    }

    public static void testNullReturnsLastGoodStale() {
        QuotaStore store = new QuotaStore();
        String fresh = "{\"fetchedAt\":1729900000000,\"providers\":[{\"id\":\"p1\",\"name\":\"P1\"}]}";
        store.resolve(fresh);

        String stale = store.resolve(null);
        assertTrue(stale != null, "stale should not be null");
        assertTrue(stale.contains("\"stale\":true"), "stale data must have stale:true");
        assertTrue(!stale.contains("\"stale\":false"), "stale data must not have stale:false");
        assertTrue(stale.contains("\"fetchedAt\":1729900000000"), "stale data must retain last good fields");
        assertTrue(stale.contains("\"p1\""), "stale data must retain last good providers");
    }

    public static void testNullWithoutHistoryReturnsEmptyStale() {
        QuotaStore store = new QuotaStore();
        String result = store.resolve(null);
        assertEquals(result, "{\"providers\":[],\"stale\":true}", "empty store must return empty providers and stale:true");
    }

    public static void testInvalidJsonTreatedAsNull() {
        QuotaStore store = new QuotaStore();
        // 1. Without prior history, invalid JSON returns empty stale
        String r1 = store.resolve("not valid json");
        assertEquals(r1, "{\"providers\":[],\"stale\":true}", "invalid json without history returns empty stale");

        String r2 = store.resolve("{broken: json");
        assertEquals(r2, "{\"providers\":[],\"stale\":true}", "malformed json without history returns empty stale");

        String r3 = store.resolve("");
        assertEquals(r3, "{\"providers\":[],\"stale\":true}", "empty string without history returns empty stale");

        // 2. With prior good history, invalid JSON returns last good with stale:true
        String fresh = "{\"fetchedAt\":1729900000000,\"providers\":[{\"id\":\"p2\",\"name\":\"P2\"}]}";
        store.resolve(fresh);

        String r4 = store.resolve("invalid-json-after-good");
        assertTrue(r4 != null, "result after invalid json should not be null");
        assertTrue(r4.contains("\"stale\":true"), "should fallback to last good with stale:true");
        assertTrue(r4.contains("\"p2\""), "should retain last good data");
    }

    public static void testErrorAfterLastGoodReturnsLastGoodStale() {
        QuotaStore store = new QuotaStore();
        String fresh = "{\"fetchedAt\":1729900000000,\"providers\":[{\"id\":\"p1\",\"name\":\"P1\"}]}";
        String rFresh = store.resolve(fresh);
        assertTrue(rFresh.contains("\"stale\":false"), "fresh data must be stale:false");

        String errorPayload = "{\"error\":\"omp_unavailable\"}";
        String rError = store.resolve(errorPayload);
        assertTrue(rError != null, "result after error should not be null");
        assertTrue(rError.contains("\"stale\":true"), "error after good data must return stale:true");
        assertTrue(rError.contains("\"p1\""), "error after good data must retain previous good providers");
        assertTrue(!rError.contains("\"error\""), "error after good data returns lastGood, not the error payload");

        String rNull = store.resolve(null);
        assertTrue(rNull.contains("\"p1\""), "subsequent null call must retain last good data");
        assertTrue(rNull.contains("\"stale\":true"), "subsequent null call must be stale:true");
    }

    public static void testLoadingFirstTimeDoesNotOverwriteLastGood() {
        QuotaStore store = new QuotaStore();
        String loadingPayload = "{\"loading\":true,\"providers\":[]}";
        String rLoading = store.resolve(loadingPayload);
        assertTrue(rLoading != null, "loading result should not be null");
        assertTrue(rLoading.contains("\"stale\":true"), "initial loading payload must be returned with stale:true");
        assertTrue(rLoading.contains("\"loading\":true"), "initial loading payload preserves loading:true");

        String rNull = store.resolve(null);
        assertEquals(rNull, "{\"providers\":[],\"stale\":true}", "loading payload must not become lastGood");
    }

    public static void main(String[] args) {
        System.out.println("Running QuotaStoreTest...");
        testFreshIsMarkedNotStale();
        System.out.println("  PASS: testFreshIsMarkedNotStale");
        testNullReturnsLastGoodStale();
        System.out.println("  PASS: testNullReturnsLastGoodStale");
        testNullWithoutHistoryReturnsEmptyStale();
        System.out.println("  PASS: testNullWithoutHistoryReturnsEmptyStale");
        testInvalidJsonTreatedAsNull();
        System.out.println("  PASS: testInvalidJsonTreatedAsNull");
        testErrorAfterLastGoodReturnsLastGoodStale();
        System.out.println("  PASS: testErrorAfterLastGoodReturnsLastGoodStale");
        testLoadingFirstTimeDoesNotOverwriteLastGood();
        System.out.println("  PASS: testLoadingFirstTimeDoesNotOverwriteLastGood");
        System.out.println("ALL QuotaStoreTest PASSED");
    }
}
