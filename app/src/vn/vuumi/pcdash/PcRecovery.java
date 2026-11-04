package vn.vuumi.pcdash;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Brings a PC back after its address changed. The host talks to a PC by the LAN address saved in the PC
 * settings; when the router restarts (a power cut) it hands out new addresses and that one is wrong for
 * good. When a PC stays unreachable, or the phone's network just changed, this looks for the agent in the
 * phone's own subnet ({@link PcLocator}) and saves the address it finds. Only an agent that proves it holds
 * the PC's token is accepted, and a PC whose address the person edited meanwhile is left alone.
 */
final class PcRecovery {
    /** Unreachable samples (30 seconds apart) before the first search. */
    static final int FAILS_BEFORE_SEARCH = 2;
    /** Wait between searches for a PC that stays unreachable (a PC that is simply off costs a quick scan). */
    static final long RESEARCH_MS = 2 * 60 * 1000L;
    static final int THREADS = 48;
    static final long SEARCH_BUDGET_MS = 20000L;

    interface Listener {
        void onAddressChanged(String pcName, String oldIp, String newIp);
    }

    private static final class State {
        int fails;
        long lastSearch;
        boolean searching;
    }

    private final PcSettings settings;
    private final PcLocator.Net net;
    private final Listener listener;
    private final TimeSource clock;
    private final Executor worker;
    private final Map<String, State> states = new HashMap<String, State>();
    private volatile long networkChangedAt;

    PcRecovery(PcSettings settings, PcLocator.Net net, Listener listener, TimeSource clock, Executor worker) {
        this.settings = settings;
        this.net = net;
        this.listener = listener;
        this.clock = clock != null ? clock : TimeSource.SYSTEM;
        this.worker = worker;
    }

    /** The phone's network changed (Wi-Fi came back, a new address): look for the PCs at the next failed sample. */
    void networkChanged() {
        networkChangedAt = clock.nowEpochMs();
    }

    void onReachable(String pcId) {
        synchronized (states) {
            State s = states.get(pcId);
            if (s != null) s.fails = 0;
        }
    }

    void onUnreachable(final PcProfile pc) {
        if (pc == null || pc.token.isEmpty()) return;
        final long now = clock.nowEpochMs();
        synchronized (states) {
            State s = states.get(pc.id);
            if (s == null) {
                s = new State();
                states.put(pc.id, s);
            }
            s.fails++;
            boolean afterNetworkChange = networkChangedAt > s.lastSearch;
            boolean due = afterNetworkChange
                    || (s.fails >= FAILS_BEFORE_SEARCH && now - s.lastSearch >= RESEARCH_MS);
            if (!due || s.searching) return;
            s.searching = true;
            s.lastSearch = now;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    search(pc);
                } finally {
                    synchronized (states) {
                        State s = states.get(pc.id);
                        if (s != null) s.searching = false;
                    }
                }
            }
        });
    }

    private void search(PcProfile pc) {
        PcProfile cur = settings.findPc(pc.id);
        if (cur == null || !cur.ip.equals(pc.ip) || cur.token.isEmpty()) return;

        // Maybe the agent was only slow: it answers at its own address after all.
        String challenge = PcLocator.newChallenge();
        String proof = net.whoami(cur.ip, cur.port, challenge, PcLocator.PROOF_TIMEOUT_MS);
        if (PcLocator.proves(cur.token, challenge, proof)) return;
        // An agent that answers but cannot prove anything is too old for this; do not wander off.
        if (PcLocator.UNSUPPORTED.equals(proof)) return;

        PcLocator.Subnet subnet = net.localSubnet(cur.ip);
        if (subnet == null) return;
        String found = PcLocator.find(cur.token, cur.port, subnet, cur.ip, net, THREADS, SEARCH_BUDGET_MS);
        if (found == null) return;

        PcProfile now = settings.findPc(pc.id);
        if (now == null || !now.ip.equals(cur.ip)) return; // the person changed it while this ran
        settings.updatePc(now.id, now.name, found, now.mac, now.port, now.token);
        if (listener != null) listener.onAddressChanged(now.name, cur.ip, found);
    }
}
