package vn.vuumi.pcdash.lock;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class AppLockTest {

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    static final class MapStore implements AppLock.Store {
        final Map<String, String> map = new HashMap<String, String>();

        @Override
        public String get(String key) {
            return map.get(key);
        }

        @Override
        public void put(String key, String value) {
            map.put(key, value);
        }

        @Override
        public void remove(String key) {
            map.remove(key);
        }
    }

    /** Stands in for the Keystore key on the JVM. */
    static final class FixedKeyHasher implements AppLock.CodeHasher {
        @Override
        public byte[] hash(byte[] salt, String code) throws GeneralSecurityException {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec("test-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(salt);
            mac.update(code.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        }
    }

    private static AppLock newLock(MapStore store) {
        return new AppLock(store, new FixedKeyHasher(), new SecureRandom());
    }

    private static void testCodeFormat() {
        assertTrue(AppLock.isValidCode("012345"), "6 digits");
        assertTrue(!AppLock.isValidCode("12345"), "5 digits");
        assertTrue(!AppLock.isValidCode("1234567"), "7 digits");
        assertTrue(!AppLock.isValidCode("12a456"), "letter");
        assertTrue(!AppLock.isValidCode(null), "null");
        System.out.println("PASS: testCodeFormat");
    }

    private static void testCodeIsNotStoredAndUnlocks() throws Exception {
        MapStore store = new MapStore();
        AppLock lock = newLock(store);
        assertTrue(!lock.isEnabled() && !lock.isLocked(), "Off by default");
        lock.setCode("482913");
        assertTrue(lock.isEnabled(), "Enabled after setCode");
        assertTrue(!lock.isLocked(), "Setting the code keeps the app unlocked");
        for (String v : store.map.values()) {
            assertTrue(!v.contains("482913"), "The code itself is never stored");
        }

        AppLock fresh = newLock(store); // a new process
        assertTrue(fresh.isLocked(), "Locked when the process starts");
        assertEquals(fresh.check("000000", 1000L).kind, AppLock.Result.WRONG, "Wrong code");
        assertTrue(fresh.isLocked(), "Still locked after a wrong code");
        assertEquals(fresh.check("482913", 1000L).kind, AppLock.Result.OK, "Right code");
        assertTrue(!fresh.isLocked(), "Unlocked");
        System.out.println("PASS: testCodeIsNotStoredAndUnlocks");
    }

    private static void testLockoutDoublesAndSurvivesRestart() throws Exception {
        MapStore store = new MapStore();
        newLock(store).setCode("111111");
        AppLock lock = newLock(store);
        long now = 10_000L;
        for (int i = 1; i < AppLock.FREE_ATTEMPTS; i++) {
            AppLock.Result r = lock.check("222222", now);
            assertEquals(r.kind, AppLock.Result.WRONG, "Wrong #" + i);
            assertEquals(r.attemptsLeft, AppLock.FREE_ATTEMPTS - i, "Attempts left #" + i);
        }
        AppLock.Result fifth = lock.check("222222", now);
        assertEquals(fifth.kind, AppLock.Result.LOCKED_OUT, "Fifth wrong code starts a lockout");
        assertEquals(fifth.retryAtMs, now + AppLock.FIRST_LOCKOUT_MS, "First lockout length");

        AppLock restarted = newLock(store);
        assertEquals(restarted.check("111111", now + 1000L).kind, AppLock.Result.LOCKED_OUT,
                "Even the right code waits; the lockout survives a restart");

        long later = now + AppLock.FIRST_LOCKOUT_MS;
        AppLock.Result sixth = restarted.check("333333", later);
        assertEquals(sixth.kind, AppLock.Result.LOCKED_OUT, "Next wrong code locks again");
        assertEquals(sixth.retryAtMs, later + 2 * AppLock.FIRST_LOCKOUT_MS, "Lockout doubles");

        long muchLater = later + 2 * AppLock.FIRST_LOCKOUT_MS;
        assertEquals(restarted.check("111111", muchLater).kind, AppLock.Result.OK, "Right code after the wait");
        assertEquals(restarted.check("444444", muchLater).kind, AppLock.Result.WRONG, "Counter reset after success");
        System.out.println("PASS: testLockoutDoublesAndSurvivesRestart");
    }

    private static void testClockGoingBackDoesNotLockForever() throws Exception {
        MapStore store = new MapStore();
        newLock(store).setCode("111111");
        store.put(AppLock.KEY_LOCKED_UNTIL, String.valueOf(Long.MAX_VALUE / 2));
        AppLock lock = newLock(store);
        long until = lock.lockedUntil(5_000L);
        assertTrue(until <= 5_000L + (AppLock.FIRST_LOCKOUT_MS << AppLock.MAX_LOCKOUT_DOUBLINGS),
                "Never waits longer than the longest lockout");
        System.out.println("PASS: testClockGoingBackDoesNotLockForever");
    }

    private static void testRelocksAfterThirtySecondsAway() throws Exception {
        MapStore store = new MapStore();
        AppLock lock = newLock(store);
        lock.setCode("123456");

        lock.onActivityStarted(0L);
        lock.onActivityStopped(1_000L);
        lock.onActivityStarted(1_000L + AppLock.RELOCK_AFTER_MS - 1);
        assertTrue(!lock.isLocked(), "Short trip away keeps it unlocked");

        // Moving between two activities of the app is not "away".
        lock.onActivityStarted(50_000L);
        lock.onActivityStopped(50_001L);
        lock.onActivityStopped(200_000L);
        lock.onActivityStarted(200_000L + AppLock.RELOCK_AFTER_MS);
        assertTrue(lock.isLocked(), "Locks after 30 s with no activity of the app on screen");

        lock.unlockWithBiometric();
        assertTrue(!lock.isLocked(), "Biometric unlocks");
        System.out.println("PASS: testRelocksAfterThirtySecondsAway");
    }

    private static void testDisableAndBiometricFlag() throws Exception {
        MapStore store = new MapStore();
        AppLock lock = newLock(store);
        lock.setBiometricEnabled(true);
        assertTrue(!lock.isBiometricEnabled(), "Biometric needs the lock on");
        lock.setCode("654321");
        lock.setBiometricEnabled(true);
        assertTrue(lock.isBiometricEnabled(), "Biometric on");
        lock.disable();
        assertTrue(!lock.isEnabled() && !lock.isLocked() && !lock.isBiometricEnabled(), "Disable clears everything");
        assertTrue(store.map.isEmpty(), "Nothing left in storage");
        System.out.println("PASS: testDisableAndBiometricFlag");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running AppLockTest...");
        testCodeFormat();
        testCodeIsNotStoredAndUnlocks();
        testLockoutDoublesAndSurvivesRestart();
        testClockGoingBackDoesNotLockForever();
        testRelocksAfterThirtySecondsAway();
        testDisableAndBiometricFlag();
        System.out.println("ALL AppLockTest PASSED");
    }
}
