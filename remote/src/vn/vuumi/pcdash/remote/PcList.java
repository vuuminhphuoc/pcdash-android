package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** The host's PCs from GET /v1/pcs: id, name, and the state of each. */
final class PcList {

    static final class Pc {
        final String id;
        final String name;
        final boolean online;
        final Integer port;
        final long rttMs;

        Pc(String id, String name, boolean online, Integer port, long rttMs) {
            this.id = id;
            this.name = name;
            this.online = online;
            this.port = port;
            this.rttMs = rttMs;
        }

        /** The same answer /v1/status would give for this PC. */
        RemoteApiClient.RemoteStatus toStatus() {
            return new RemoteApiClient.RemoteStatus(online, port, rttMs, null);
        }
    }

    final List<Pc> pcs;

    private PcList(List<Pc> pcs) {
        this.pcs = Collections.unmodifiableList(pcs);
    }

    /** The PC with this id, else the first; null when the list is empty. */
    Pc pick(String id) {
        for (Pc p : pcs) {
            if (p.id.equals(id)) return p;
        }
        return pcs.isEmpty() ? null : pcs.get(0);
    }

    /** Null for missing or malformed JSON. */
    static PcList parse(String json) {
        Map<String, Object> root = MetricsParser.parseMap(json);
        if (root == null || !(root.get("pcs") instanceof List)) return null;
        List<Pc> out = new ArrayList<Pc>();
        for (Object o : (List<?>) root.get("pcs")) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            Object id = m.get("id");
            if (!(id instanceof String) || ((String) id).isEmpty()) continue;
            Object name = m.get("name");
            Object port = m.get("port");
            Object rtt = m.get("rtt_ms");
            out.add(new Pc((String) id,
                    name instanceof String && !((String) name).isEmpty() ? (String) name : "PC",
                    Boolean.TRUE.equals(m.get("online")),
                    port instanceof Number ? Integer.valueOf(((Number) port).intValue()) : null,
                    rtt instanceof Number ? ((Number) rtt).longValue() : 0L));
        }
        return new PcList(out);
    }
}
