package vn.vuumi.pcdash;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * The PCs this host controls, as the remote API sees them: status of all of them at once, and per PC
 * the actions, metrics, AI quota and the 6-hour history. A null PC id always means the first PC, so a
 * remote that knows nothing about several PCs keeps working.
 */
final class PcFleet {
    private final PcSettings settings;
    private final File dir;
    private final MetricsHistory firstHistory;
    private final Map<String, PcMetricsClient> metricsClients = new ConcurrentHashMap<String, PcMetricsClient>();
    private final Map<String, PcQuotaClient> quotaClients = new ConcurrentHashMap<String, PcQuotaClient>();
    private final Map<String, QuotaStore> quotaStores = new ConcurrentHashMap<String, QuotaStore>();
    private final Map<String, MetricsHistory> histories = new ConcurrentHashMap<String, MetricsHistory>();
    private final ExecutorService probes = Executors.newCachedThreadPool(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "pcdash-fleet");
            t.setDaemon(true);
            return t;
        }
    });

    /**
     * @param firstHistory the history file the single-PC host always had; it stays with the first PC's id
     */
    PcFleet(PcSettings settings, File dir, MetricsHistory firstHistory) {
        this.settings = settings;
        this.dir = dir;
        this.firstHistory = firstHistory;
    }

    PcSettings settings() {
        return settings;
    }

    /** Every PC with its current state; the PCs are probed at the same time. */
    String pcsJson() {
        List<PcProfile> pcs = settings.listPcs();
        List<Future<String>> states = new ArrayList<Future<String>>();
        for (final PcProfile pc : pcs) {
            states.add(probes.submit(new java.util.concurrent.Callable<String>() {
                @Override
                public String call() {
                    return PcConn.statusJson(pc);
                }
            }));
        }
        StringBuilder sb = new StringBuilder("{\"pcs\":[");
        for (int i = 0; i < pcs.size(); i++) {
            String status;
            try {
                status = states.get(i).get(PcConn.STATUS_TIMEOUT + 2000L, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                status = "{\"online\":false,\"port\":null,\"rtt_ms\":0,\"configured\":true}";
            }
            if (i > 0) sb.append(',');
            sb.append(entryJson(pcs.get(i), status));
        }
        return sb.append("]}").toString();
    }

    /** {"id":..,"name":..} merged in front of one PC's status object. */
    static String entryJson(PcProfile pc, String statusJson) {
        String rest = statusJson != null && statusJson.trim().startsWith("{") ? statusJson.trim().substring(1) : "}";
        return "{\"id\":" + jsonString(pc.id) + ",\"name\":" + jsonString(pc.name)
                + (rest.equals("}") ? "}" : "," + rest);
    }

    String statusJson(String pcId) {
        return PcConn.statusJson(settings.findPc(pcId));
    }

    String doAction(String pcId, String action) {
        return PcConn.doAction(settings.findPc(pcId), action);
    }

    String metricsJson(String pcId, PhoneMetricsSource phone) {
        return MetricsJson.build(metricsClient(pcId), phone);
    }

    String quotaJson(String pcId, boolean refresh) {
        String id = idOf(pcId);
        if (id == null) return null;
        PcQuotaClient client = quotaClients.get(id);
        if (client == null) {
            client = new PcQuotaClient(settings.source(id));
            quotaClients.put(id, client);
        }
        QuotaStore store = quotaStores.get(id);
        if (store == null) {
            store = new QuotaStore();
            quotaStores.put(id, store);
        }
        return store.resolve(client.getQuotaJson(refresh));
    }

    String historyJson(String pcId, int minutes) {
        String id = idOf(pcId);
        return id != null ? history(id).toHistoryJson(System.currentTimeMillis(), minutes) : null;
    }

    /** The PC's name for the command log, when there is more than one PC. */
    String label(String pcId) {
        List<PcProfile> pcs = settings.listPcs();
        if (pcs.size() < 2) return null;
        PcProfile pc = settings.findPc(pcId);
        return pc != null ? pc.name : null;
    }

    /** One history sample for every PC, all at once (a PC that is off only costs its own timeout). */
    void sampleAll(final long now, final boolean save) {
        List<Future<?>> done = new ArrayList<Future<?>>();
        for (final PcProfile pc : settings.listPcs()) {
            done.add(probes.submit(new Runnable() {
                @Override
                public void run() {
                    MetricsHistory h = history(pc.id);
                    h.add(MetricsHistory.parseAgentMetrics(now, metricsClient(pc.id).getMetricsJson()));
                    if (save) h.save();
                }
            }));
        }
        for (Future<?> f : done) {
            try {
                f.get(25, TimeUnit.SECONDS);
            } catch (Exception ignored) {}
        }
    }

    void saveAll() {
        for (MetricsHistory h : histories.values()) {
            h.save();
        }
        if (firstHistory != null) firstHistory.save();
    }

    private PcMetricsClient metricsClient(String pcId) {
        String id = idOf(pcId);
        String key = id != null ? id : "";
        PcMetricsClient c = metricsClients.get(key);
        if (c == null) {
            c = new PcMetricsClient(settings.source(id));
            metricsClients.put(key, c);
        }
        return c;
    }

    private MetricsHistory history(String id) {
        if (firstHistory != null && PcSettings.FIRST_PC_ID.equals(id)) {
            return firstHistory;
        }
        MetricsHistory h = histories.get(id);
        if (h == null) {
            h = new MetricsHistory(new File(dir, "metrics_history_" + id + ".json"));
            histories.put(id, h);
        }
        return h;
    }

    // The PC's id, or the first PC's for null; null when there is no such PC.
    private String idOf(String pcId) {
        PcProfile pc = settings.findPc(pcId);
        return pc != null ? pc.id : null;
    }

    static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
