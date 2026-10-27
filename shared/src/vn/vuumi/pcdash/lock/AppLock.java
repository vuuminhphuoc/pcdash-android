package vn.vuumi.pcdash.lock;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * App lock with a 6-digit passcode. The code is never stored: only HMAC(salt, code) under a key the
 * {@link CodeHasher} keeps (Android Keystore in the apps), so a copied settings file cannot be
 * brute-forced off the phone. On the phone, 5 wrong codes start a lockout that doubles each time.
 *
 * The lock closes when the process starts, and then by the user's choice: when the app comes back after
 * some time with none of its activities started (immediately, 30 s, 1 or 5 min), or only when the phone's
 * screen turns off. Not Android-specific, so it is tested on the JVM.
 */
public final class AppLock {

    /** Small key/value storage (SharedPreferences in the apps). */
    public interface Store {
        String get(String key);

        void put(String key, String value);

        void remove(String key);
    }

    /** Keyed hash of salt + code. */
    public interface CodeHasher {
        byte[] hash(byte[] salt, String code) throws GeneralSecurityException;
    }

    public static final int CODE_LENGTH = 6;
    /** Default time away before the lock closes again. */
    public static final long RELOCK_AFTER_MS = 30_000L;
    /** Relock choice: only when the phone's screen turns off, however long the app was away. */
    public static final long RELOCK_ON_SCREEN_OFF = -1L;
    /** The relock choices offered in settings, in order. */
    public static final long[] RELOCK_CHOICES = {0L, RELOCK_AFTER_MS, 60_000L, 300_000L, RELOCK_ON_SCREEN_OFF};
    public static final int FREE_ATTEMPTS = 5;
    public static final long FIRST_LOCKOUT_MS = 30_000L;
    // 30 s doubling to 16 min: 5 tries per lockout make guessing 1 of 10^6 codes take years.
    public static final int MAX_LOCKOUT_DOUBLINGS = 5;

    static final String KEY_ENABLED = "enabled";
    static final String KEY_SALT = "salt";
    static final String KEY_HASH = "hash";
    static final String KEY_BIOMETRIC = "biometric";
    static final String KEY_FAILURES = "failures";
    static final String KEY_LOCKED_UNTIL = "lockedUntil";
    static final String KEY_RELOCK = "relock";

    /** Outcome of {@link #check}. */
    public static final class Result {
        public static final int OK = 0;
        public static final int WRONG = 1;
        public static final int LOCKED_OUT = 2;

        public final int kind;
        /** WRONG: tries left before a lockout. */
        public final int attemptsLeft;
        /** LOCKED_OUT: wall-clock time when the next try is allowed. */
        public final long retryAtMs;

        Result(int kind, int attemptsLeft, long retryAtMs) {
            this.kind = kind;
            this.attemptsLeft = attemptsLeft;
            this.retryAtMs = retryAtMs;
        }
    }

    private final Store store;
    private final CodeHasher hasher;
    private final SecureRandom random;

    private boolean unlocked = false;
    private int startedActivities = 0;
    private long backgroundSinceMs = -1L;

    public AppLock(Store store, CodeHasher hasher, SecureRandom random) {
        this.store = store;
        this.hasher = hasher;
        this.random = random != null ? random : new SecureRandom();
    }

    public static boolean isValidCode(String code) {
        if (code == null || code.length() != CODE_LENGTH) return false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    public synchronized boolean isEnabled() {
        return "1".equals(store.get(KEY_ENABLED)) && store.get(KEY_HASH) != null;
    }

    /** True while the app must show the lock screen. */
    public synchronized boolean isLocked() {
        return isEnabled() && !unlocked;
    }

    public synchronized boolean isBiometricEnabled() {
        return isEnabled() && "1".equals(store.get(KEY_BIOMETRIC));
    }

    public synchronized void setBiometricEnabled(boolean enabled) {
        if (enabled) {
            store.put(KEY_BIOMETRIC, "1");
        } else {
            store.remove(KEY_BIOMETRIC);
        }
    }

    /** Turns the lock on (or changes the code). The app stays unlocked: the user just typed it. */
    public synchronized void setCode(String code) throws GeneralSecurityException {
        if (!isValidCode(code)) {
            throw new IllegalArgumentException("Passcode must be " + CODE_LENGTH + " digits");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        byte[] hash = hasher.hash(salt, code);
        store.put(KEY_SALT, toHex(salt));
        store.put(KEY_HASH, toHex(hash));
        store.put(KEY_ENABLED, "1");
        store.remove(KEY_FAILURES);
        store.remove(KEY_LOCKED_UNTIL);
        unlock();
    }

    public synchronized void disable() {
        store.remove(KEY_ENABLED);
        store.remove(KEY_SALT);
        store.remove(KEY_HASH);
        store.remove(KEY_BIOMETRIC);
        store.remove(KEY_FAILURES);
        store.remove(KEY_LOCKED_UNTIL);
        store.remove(KEY_RELOCK);
        unlock();
    }

    /** Checks a typed code; a correct one unlocks the app. */
    public synchronized Result check(String code, long nowMs) {
        long lockedUntil = lockedUntil(nowMs);
        if (lockedUntil > nowMs) {
            return new Result(Result.LOCKED_OUT, 0, lockedUntil);
        }
        if (matches(code)) {
            store.remove(KEY_FAILURES);
            store.remove(KEY_LOCKED_UNTIL);
            unlock();
            return new Result(Result.OK, FREE_ATTEMPTS, 0L);
        }
        int failures = parseInt(store.get(KEY_FAILURES)) + 1;
        store.put(KEY_FAILURES, String.valueOf(failures));
        if (failures >= FREE_ATTEMPTS) {
            int doublings = Math.min(failures - FREE_ATTEMPTS, MAX_LOCKOUT_DOUBLINGS);
            long until = nowMs + (FIRST_LOCKOUT_MS << doublings);
            store.put(KEY_LOCKED_UNTIL, String.valueOf(until));
            return new Result(Result.LOCKED_OUT, 0, until);
        }
        return new Result(Result.WRONG, FREE_ATTEMPTS - failures, 0L);
    }

    /** Biometric prompt succeeded. */
    public synchronized void unlockWithBiometric() {
        store.remove(KEY_FAILURES);
        store.remove(KEY_LOCKED_UNTIL);
        unlock();
    }

    // An unlock while no activity is started (a fingerprint prompt shown as its own activity) must not
    // count as time away when the app's activity comes back.
    private void unlock() {
        unlocked = true;
        backgroundSinceMs = -1L;
    }

    /** One of {@link #RELOCK_CHOICES}: time away in ms, or {@link #RELOCK_ON_SCREEN_OFF}. */
    public synchronized long relockAfterMs() {
        String v = store.get(KEY_RELOCK);
        if (v == null) return RELOCK_AFTER_MS;
        try {
            long ms = Long.parseLong(v);
            for (long choice : RELOCK_CHOICES) {
                if (choice == ms) return ms;
            }
        } catch (NumberFormatException ignored) {}
        return RELOCK_AFTER_MS;
    }

    public synchronized void setRelockAfterMs(long ms) {
        for (long choice : RELOCK_CHOICES) {
            if (choice == ms) {
                store.put(KEY_RELOCK, String.valueOf(ms));
                return;
            }
        }
        throw new IllegalArgumentException("Not a relock choice: " + ms);
    }

    /** When the next code may be tried; 0 when not locked out. */
    public synchronized long lockedUntil(long nowMs) {
        long until = parseLong(store.get(KEY_LOCKED_UNTIL));
        long longest = FIRST_LOCKOUT_MS << MAX_LOCKOUT_DOUBLINGS;
        if (until - nowMs > longest) {
            // The clock went backwards; never wait longer than the longest lockout.
            until = nowMs + longest;
            store.put(KEY_LOCKED_UNTIL, String.valueOf(until));
        }
        return until > nowMs ? until : 0L;
    }

    /**
     * An activity of the app became visible. Locks again after a long enough time away.
     *
     * @param nowMs a clock that never goes back (elapsed time since boot on the phone)
     */
    public synchronized void onActivityStarted(long nowMs) {
        long after = relockAfterMs();
        if (startedActivities == 0 && backgroundSinceMs >= 0 && after != RELOCK_ON_SCREEN_OFF
                && nowMs - backgroundSinceMs >= after) {
            unlocked = false;
        }
        startedActivities++;
    }

    /**
     * @param changingConfigurations the activity stops only to be recreated at once (rotation, dark
     *                               mode): not time away, even with the "immediately" choice
     */
    public synchronized void onActivityStopped(long nowMs, boolean changingConfigurations) {
        startedActivities = Math.max(0, startedActivities - 1);
        if (startedActivities == 0) {
            backgroundSinceMs = changingConfigurations ? -1L : nowMs;
        }
    }

    /** The phone's screen turned off. Returns true when that closed the lock. */
    public synchronized boolean onScreenOff() {
        if (!isEnabled() || !unlocked || relockAfterMs() != RELOCK_ON_SCREEN_OFF) return false;
        unlocked = false;
        return true;
    }

    private boolean matches(String code) {
        if (!isValidCode(code)) return false;
        String saltHex = store.get(KEY_SALT);
        String hashHex = store.get(KEY_HASH);
        if (saltHex == null || hashHex == null) return false;
        try {
            byte[] expected = fromHex(hashHex);
            byte[] actual = hasher.hash(fromHex(saltHex), code);
            return MessageDigest.isEqual(expected, actual);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    private static int parseInt(String s) {
        try {
            return s != null ? Integer.parseInt(s) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long parseLong(String s) {
        try {
            return s != null ? Long.parseLong(s) : 0L;
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // Hex rather than java.util.Base64, which needs API 26 (the remote runs on API 24).
    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    static byte[] fromHex(String hex) {
        if (hex.length() % 2 != 0) throw new IllegalArgumentException("Odd hex length");
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(2 * i), 16);
            int lo = Character.digit(hex.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("Bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
