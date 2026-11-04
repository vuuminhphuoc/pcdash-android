package vn.vuumi.pcdash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Finds a PC whose LAN address changed (the router restarted and handed out new ones). The host tries every
 * address of its own subnet on the agent port and asks each for proof that it holds the agent token: the
 * agent answers a random challenge with an HMAC keyed by the token, so the token itself never crosses the
 * LAN and another device that merely listens on that port cannot be mistaken for the PC.
 */
final class PcLocator {
    /** The smallest subnet that is searched (a /22 is 1022 addresses); a wider one is cut down to this. */
    static final int MIN_PREFIX = 22;
    static final int MAX_PREFIX = 30;
    static final int CONNECT_TIMEOUT_MS = 400;
    static final int PROOF_TIMEOUT_MS = 1500;

    /** Answer of {@link Net#whoami} from an agent that answers HTTP but has no proof route (an older one). */
    static final String UNSUPPORTED = "";

    private static final SecureRandom RANDOM = new SecureRandom();

    interface Net {
        boolean isOpen(String ip, int port, int timeoutMs);

        /**
         * The proof the agent at this address gave for the challenge, {@link #UNSUPPORTED} when it answered
         * without one, null when there was no usable answer at all.
         */
        String whoami(String ip, int port, String challenge, int timeoutMs);

        /** This phone's address and prefix length on the subnet that holds {@code nearIp}; null when it has none. */
        Subnet localSubnet(String nearIp);
    }

    static final class Subnet {
        final String ip;
        final int prefix;

        Subnet(String ip, int prefix) {
            this.ip = ip;
            this.prefix = prefix;
        }
    }

    private PcLocator() {}

    static String newChallenge() {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    /** True when {@code proof} is what an agent holding {@code token} answers for {@code challenge}. */
    static boolean proves(String token, String challenge, String proof) {
        if (token == null || token.isEmpty() || proof == null || proof.isEmpty()) return false;
        String expected = AgentAuth.signature(token, "whoami", challenge, "GET", "/whoami", null);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                proof.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    /** The addresses of the subnet except this phone's, the network and broadcast addresses and {@code skipIp}. */
    static List<String> candidates(Subnet subnet, String skipIp) {
        List<String> out = new ArrayList<String>();
        long own = toLong(subnet.ip);
        if (own < 0) return out;
        int prefix = Math.max(MIN_PREFIX, Math.min(MAX_PREFIX, subnet.prefix));
        long mask = (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
        long base = own & mask;
        long count = (1L << (32 - prefix)) - 2;
        long skip = skipIp != null ? toLong(skipIp) : -1;
        for (long h = 1; h <= count; h++) {
            long a = base + h;
            if (a == own || a == skip) continue;
            out.add(toIp(a));
        }
        return out;
    }

    /**
     * The address in the subnet where an agent proves it holds {@code token}, or null. Everything is tried
     * at once; the search stops at the first proof, or after {@code budgetMs}.
     */
    static String find(final String token, final int port, Subnet subnet, String skipIp, final Net net,
                       int threads, long budgetMs) {
        if (token == null || token.isEmpty()) return null;
        final String challenge = newChallenge();
        final AtomicReference<String> found = new AtomicReference<String>();
        final ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, threads), new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "pcdash-locator");
                t.setDaemon(true);
                return t;
            }
        });
        for (final String ip : candidates(subnet, skipIp)) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (found.get() != null) return;
                    if (!net.isOpen(ip, port, CONNECT_TIMEOUT_MS)) return;
                    String proof = net.whoami(ip, port, challenge, PROOF_TIMEOUT_MS);
                    if (proves(token, challenge, proof)) found.compareAndSet(null, ip);
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(budgetMs, TimeUnit.MILLISECONDS)) pool.shutdownNow();
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return found.get();
    }

    static long toLong(String ip) {
        if (ip == null) return -1;
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) return -1;
        long v = 0;
        try {
            for (String s : p) {
                int n = Integer.parseInt(s);
                if (n < 0 || n > 255) return -1;
                v = (v << 8) | n;
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return v;
    }

    static String toIp(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }
}
