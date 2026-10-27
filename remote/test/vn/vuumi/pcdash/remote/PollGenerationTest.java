package vn.vuumi.pcdash.remote;

public class PollGenerationTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(int actual, int expected, String msg) {
        if (actual != expected) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    public static void testInitialState() {
        PollGeneration gen = new PollGeneration();
        assertEquals(gen.current(), 0, "Initial generation should be 0");
        assertTrue(!gen.isValid(1), "Generation 1 should not be valid initially");
        assertTrue(gen.isValid(0), "Generation 0 is valid initially");
    }

    public static void testNextIncrementsAndValidates() {
        PollGeneration gen = new PollGeneration();
        int g1 = gen.next();
        assertEquals(g1, 1, "First next() should be 1");
        assertTrue(gen.isValid(g1), "Generation 1 should be valid");
        assertTrue(!gen.isValid(0), "Generation 0 should no longer be valid");

        int g2 = gen.next();
        assertEquals(g2, 2, "Second next() should be 2");
        assertTrue(gen.isValid(g2), "Generation 2 should be valid");
        assertTrue(!gen.isValid(g1), "Generation 1 should now be invalid");
    }

    public static void testInvalidateDeprecatesCurrent() {
        PollGeneration gen = new PollGeneration();
        int g1 = gen.next();
        assertTrue(gen.isValid(g1), "g1 should be valid before invalidate");

        gen.invalidate();
        assertTrue(!gen.isValid(g1), "g1 should be invalid after invalidate");
        assertEquals(gen.current(), 2, "invalidate() increments generation");
    }

    public static void main(String[] args) {
        System.out.println("Running PollGenerationTest...");
        testInitialState();
        System.out.println("  PASS: testInitialState");
        testNextIncrementsAndValidates();
        System.out.println("  PASS: testNextIncrementsAndValidates");
        testInvalidateDeprecatesCurrent();
        System.out.println("  PASS: testInvalidateDeprecatesCurrent");
        System.out.println("ALL PollGenerationTest PASSED");
    }
}
