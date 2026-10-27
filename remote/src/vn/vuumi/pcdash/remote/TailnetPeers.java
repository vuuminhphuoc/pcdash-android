package vn.vuumi.pcdash.remote;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import vn.vuumi.pcdash.i18n.L10n;

/**
 * The Tailscale device list as this phone's tailnet node sees it (PcnetBridge.peersJson()): every
 * other device of the tailnet and whether the coordination server has it online. Reading it sends
 * nothing to the devices themselves, so it answers "is the home phone online" without waiting on
 * a request that may never come back.
 */
final class TailnetPeers {

    static final class Peer {
        final String hostName;
        final List<String> ips;
        final boolean online;
        /** When the device was last connected, if it is offline; -1 when unknown. */
        final long lastSeenMs;
        /** The home phone this remote is paired with. */
        final boolean target;

        Peer(String hostName, List<String> ips, boolean online, long lastSeenMs, boolean target) {
            this.hostName = hostName;
            this.ips = ips;
            this.online = online;
            this.lastSeenMs = lastSeenMs;
            this.target = target;
        }
    }

    final String backendState;
    final List<Peer> peers;

    private TailnetPeers(String backendState, List<Peer> peers) {
        this.backendState = backendState;
        this.peers = Collections.unmodifiableList(peers);
    }

    /** The paired home phone, or null when it is not in the list (yet). */
    Peer host() {
        for (Peer p : peers) {
            if (p.target) return p;
        }
        return null;
    }

    /** Null for missing or malformed JSON. */
    static TailnetPeers parse(String json) {
        Map<String, Object> root = MetricsParser.parseMap(json);
        if (root == null) return null;
        Object state = root.get("backendState");
        Object list = root.get("peers");
        if (!(list instanceof List)) return null;
        List<Peer> peers = new ArrayList<Peer>();
        for (Object o : (List<?>) list) {
            if (!(o instanceof Map)) continue;
            Map<?, ?> m = (Map<?, ?>) o;
            List<String> ips = new ArrayList<String>();
            if (m.get("ips") instanceof List) {
                for (Object ip : (List<?>) m.get("ips")) {
                    if (ip instanceof String) ips.add((String) ip);
                }
            }
            peers.add(new Peer(
                    m.get("hostName") instanceof String ? (String) m.get("hostName") : "",
                    Collections.unmodifiableList(ips),
                    Boolean.TRUE.equals(m.get("online")),
                    parseTime(m.get("lastSeen")),
                    Boolean.TRUE.equals(m.get("target"))));
        }
        return new TailnetPeers(state instanceof String ? (String) state : "", peers);
    }

    /** "5 min ago" style text for a last-seen time; empty when unknown. */
    static String ago(long thenMs, long nowMs) {
        if (thenMs < 0) return "";
        long min = Math.max(0L, nowMs - thenMs) / 60_000L;
        if (min < 1) return L10n.t("just now", "vừa xong");
        if (min < 60) return L10n.t(min + " min ago", min + " phút trước");
        long hours = min / 60;
        if (hours < 48) return L10n.t(hours + " h ago", hours + " giờ trước");
        long days = hours / 24;
        return L10n.t(days + " days ago", days + " ngày trước");
    }

    // RFC 3339 in UTC, as the Go side writes it ("2026-10-27T13:05:00Z"). java.time needs API 26.
    private static long parseTime(Object value) {
        if (!(value instanceof String) || ((String) value).isEmpty()) return -1L;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        try {
            return f.parse((String) value).getTime();
        } catch (ParseException e) {
            return -1L;
        }
    }
}
