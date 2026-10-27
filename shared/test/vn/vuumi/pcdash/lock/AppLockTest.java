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
        lock.onActivityStopped(1_000L, false);
        lock.onActivityStarted(1_000L + AppLock.RELOCK_AFTER_MS - 1);
        assertTrue(!lock.isLocked(), "Short trip away keeps it unlocked");

        // Moving between two activities of the app is not "away".
        lock.onActivityStarted(50_000L);
        lock.onActivityStopped(50_001L, false);
        lock.onActivityStopped(200_000L, false);
        lock.onActivityStarted(200_000L + AppLock.RELOCK_AFTER_MS);
        assertTrue(lock.isLocked(), "Locks after 30 s with no activity of the app on screen");

        lock.unlockWithBiometric();
        assertTrue(!lock.isLocked(), "Biometric unlocks");
        System.out.println("PASS: testRelocksAfterThirtySecondsAway");
    }

    private static void testRelockChoices() throws Exception {
        MapStore store = new MapStore();
        AppLock lock = newLock(store);
        lock.setCode("123456");
        assertEquals(lock.relockAfterMs(), AppLock.RELOCK_AFTER_MS, "30 s by default");

        lock.setRelockAfterMs(0L);
        lock.onActivityStarted(0L);
        lock.onActivityStopped(10L, false);
        lock.onActivityStarted(10L);
        assertTrue(lock.isLocked(), "Immediately: any trip away locks");

        lock.check("123456", 0L);
        lock.onActivityStopped(20L, true);
        lock.onActivityStarted(20L);
        assertTrue(!lock.isLocked(), "Immediately: a rotation is not a trip away");

        lock.setRelockAfterMs(300_000L);
        lock.onActivityStopped(1_000L, false);
        lock.onActivityStarted(1_000L + 299_999L);
        assertTrue(!lock.isLocked(), "5 min: still open just before");
        lock.onActivityStopped(400_000L, false);
        lock.onActivityStarted(700_000L);
        assertTrue(lock.isLocked(), "5 min: locked after");

        store.put(AppLock.KEY_RELOCK, "12345");
        assertEquals(lock.relockAfterMs(), AppLock.RELOCK_AFTER_MS, "Unknown stored value falls back to 30 s");
        boolean threw = false;
        try {
            lock.setRelockAfterMs(12345L);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "Only the offered choices can be set");
        System.out.println("PASS: testRelockChoices");
    }

    private static void testScreenOffChoice() throws Exception {
        MapStore store = new MapStore();
        AppLock lock = newLock(store);
        lock.setCode("123456");
        assertTrue(!lock.onScreenOff(), "Timed choices ignore the screen");
        assertTrue(!lock.isLocked(), "Still open with the 30 s choice");

        lock.setRelockAfterMs(AppLock.RELOCK_ON_SCREEN_OFF);
        lock.onActivityStarted(0L);
        lock.onActivityStopped(1_000L, false);
        lock.onActivityStarted(1_000L + 24L * 3600_000L);
        assertTrue(!lock.isLocked(), "A day away with the screen on stays open");

        assertTrue(lock.onScreenOff(), "Screen off closes the lock");
        assertTrue(lock.isLocked(), "Locked");
        assertTrue(!lock.onScreenOff(), "Already locked: nothing new to report");

        lock.unlockWithBiometric();
        assertTrue(!lock.isLocked(), "Unlocked again");
        lock.disable();
        assertTrue(!lock.onScreenOff(), "Lock off: the screen does nothing");
        assertEquals(store.map.get(AppLock.KEY_RELOCK), null, "Disable forgets the choice");
        System.out.println("PASS: testScreenOffChoice");
    }

    private static void testUnlockWhileAwayIsNotTimeAway() throws Exception {
        MapStore store = new MapStore();
        AppLock lock = newLock(store);
        lock.setCode("123456");
        lock.setRelockAfterMs(0L);
        lock.onActivityStarted(0L);
        lock.onActivityStopped(5L, false);
        lock.onActivityStarted(6L);
        assertTrue(lock.isLocked(), "Locked on return");
        // Some phones show the fingerprint prompt as an activity: ours stops, the unlock comes while away.
        lock.onActivityStopped(7L, false);
        lock.unlockWithBiometric();
        lock.onActivityStarted(9L);
        assertTrue(!lock.isLocked(), "Coming back from the prompt keeps the unlock");
        System.out.println("PASS: testUnlockWhileAwayIsNotTimeAway");
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
        testRelockChoices();
        testScreenOffChoice();
        testUnlockWhileAwayIsNotTimeAway();
        testDisableAndBiometricFlag();
        System.out.println("ALL AppLockTest PASSED");
    }
}
