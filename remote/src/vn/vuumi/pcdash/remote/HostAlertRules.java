package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * When a background check should notify. The home phone counts as offline at once when the Tailscale
 * device list says so (the coordination server already waited before marking it offline); when the
 * list says online but the PCDash host app does not answer, one check is not enough (it can land on a
 * blip). Each PC, as the home phone reports it, also needs two checks, and a PC this app was just told
 * to put to sleep or shut down is expected to go off. Each alert fires once and re-arms when the
 * problem clears.
 */
final class HostAlertRules {
    static final int OFFLINE_AFTER_FAILED_CHECKS = 2;
    static final double LOW_BATTERY_PERCENT = 20.0;
    // Charging, or back to this level, clears a low-battery alert so the next drop alerts again.
    static final double BATTERY_REARM_PERCENT = 30.0;

    enum Alert {
        /** Offline in the Tailscale device list. */
        HOST_OFFLINE,
        /** Online in the device list, but the PCDash host app does not answer. */
        HOST_NOT_ANSWERING,
        HOST_BACK_ONLINE,
        BATTERY_LOW,
        PC_OFFLINE,
        PC_BACK_ONLINE
    }

    /** One notification to post: what happened, and to which PC (null for the home phone). */
    static final class Event {
        final Alert alert;
        final String pcId;

        Event(Alert alert, String pcId) {
            this.alert = alert;
            this.pcId = pcId;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Event)) return false;
            Event e = (Event) o;
            return alert == e.alert && (pcId == null ? e.pcId == null : pcId.equals(e.pcId));
        }

        @Override
        public int hashCode() {
            return alert.hashCode() * 31 + (pcId != null ? pcId.hashCode() : 0);
        }

        @Override
        public String toString() {
            return alert + (pcId != null ? "(" + pcId + ")" : "");
        }
    }

    /** What one check found. */
    static final class Check {
        /** The home phone in the Tailscale device list; null when the list did not show it. */
        final Boolean hostListedOnline;
        /** The PCDash host app answered. */
        final boolean hostAnswered;
        /**
         * Each PC (by id) as the home phone sees it; null when it did not answer. A host with one PC
         * reports it under "".
         */
        final Map<String, Boolean> pcs;
        final Double batteryPercent;
        final Boolean charging;

        Check(Boolean hostListedOnline, boolean hostAnswered, Map<String, Boolean> pcs, Double batteryPercent, Boolean charging) {
            this.hostListedOnline = hostListedOnline;
            this.hostAnswered = hostAnswered;
            this.pcs = pcs;
            this.batteryPercent = batteryPercent;
            this.charging = charging;
        }
    }

    /** Per PC, kept between checks. */
    static final class PcState {
        int failedChecks;
        boolean alerted;
        // Set when this app sent Sleep / Hibernate / Shut down to it; cleared once it is back on.
        boolean offExpected;
    }

    /** Kept between checks (HostWatch stores it as text). */
    static final class State {
        int failedChecks;
        boolean offlineAlerted;
        boolean batteryAlerted;
        final Map<String, PcState> pcs = new LinkedHashMap<String, PcState>();

        PcState pc(String id) {
            String key = id != null ? id : "";
            PcState p = pcs.get(key);
            if (p == null) {
                p = new PcState();
                pcs.put(key, p);
            }
            return p;
        }

        // "f,o,b" then ";id:failed:alerted:expected" per PC (ids never contain ':' ',' or ';').
        String encode() {
            StringBuilder sb = new StringBuilder();
            sb.append(failedChecks).append(',').append(bit(offlineAlerted)).append(',').append(bit(batteryAlerted));
            for (Map.Entry<String, PcState> e : pcs.entrySet()) {
                PcState p = e.getValue();
                sb.append(';').append(e.getKey()).append(':').append(p.failedChecks).append(':')
                        .append(bit(p.alerted)).append(':').append(bit(p.offExpected));
            }
            return sb.toString();
        }

        static State decode(String s) {
            State st = new State();
            if (s == null) return st;
            try {
                String[] sections = s.split(";", -1);
                String[] parts = sections[0].split(",");
                // 3 fields, or 6 when the previous version kept one PC in the same line.
                if (parts.length == 3 || parts.length == 6) {
                    st.failedChecks = Math.max(0, Integer.parseInt(parts[0]));
                    st.offlineAlerted = "1".equals(parts[1]);
                    st.batteryAlerted = "1".equals(parts[2]);
                }
                if (parts.length == 6) {
                    PcState p = st.pc("");
                    p.failedChecks = Math.max(0, Integer.parseInt(parts[3]));
                    p.alerted = "1".equals(parts[4]);
                    p.offExpected = "1".equals(parts[5]);
                }
                for (int i = 1; i < sections.length; i++) {
                    String[] f = sections[i].split(":", -1);
                    if (f.length != 4) continue;
                    PcState p = st.pc(f[0]);
                    p.failedChecks = Math.max(0, Integer.parseInt(f[1]));
                    p.alerted = "1".equals(f[2]);
                    p.offExpected = "1".equals(f[3]);
                }
            } catch (NumberFormatException ignored) {
                return new State();
            }
            return st;
        }

        private static String bit(boolean b) {
            return b ? "1" : "0";
        }
    }

    private HostAlertRules() {}

    /** Updates the state with one check and returns the notifications to post. */
    static List<Event> apply(State state, Check check, boolean pcAlerts) {
        List<Event> events = new ArrayList<Event>();
        if (!check.hostAnswered) {
            boolean listedOffline = Boolean.FALSE.equals(check.hostListedOnline);
            state.failedChecks = listedOffline
                    ? Math.max(state.failedChecks + 1, OFFLINE_AFTER_FAILED_CHECKS)
                    : state.failedChecks + 1;
            if (state.failedChecks >= OFFLINE_AFTER_FAILED_CHECKS && !state.offlineAlerted) {
                state.offlineAlerted = true;
                events.add(new Event(listedOffline ? Alert.HOST_OFFLINE : Alert.HOST_NOT_ANSWERING, null));
            }
            // Nothing is known about the PCs or the battery while the home phone is silent.
            return events;
        }
        state.failedChecks = 0;
        if (state.offlineAlerted) {
            state.offlineAlerted = false;
            events.add(new Event(Alert.HOST_BACK_ONLINE, null));
        }
        if (check.batteryPercent != null) {
            boolean charging = Boolean.TRUE.equals(check.charging);
            if (state.batteryAlerted) {
                if (charging || check.batteryPercent >= BATTERY_REARM_PERCENT) {
                    state.batteryAlerted = false;
                }
            } else if (!charging && check.batteryPercent <= LOW_BATTERY_PERCENT) {
                state.batteryAlerted = true;
                events.add(new Event(Alert.BATTERY_LOW, null));
            }
        }
        if (check.pcs != null) {
            // PCs removed on the home phone are forgotten.
            state.pcs.keySet().retainAll(check.pcs.keySet());
            for (Map.Entry<String, Boolean> e : check.pcs.entrySet()) {
                applyPc(e.getKey(), state.pc(e.getKey()), Boolean.TRUE.equals(e.getValue()), pcAlerts, events);
            }
        }
        return events;
    }

    private static void applyPc(String id, PcState state, boolean online, boolean pcAlerts, List<Event> events) {
        if (online) {
            state.failedChecks = 0;
            state.offExpected = false;
            if (state.alerted) {
                state.alerted = false;
                if (pcAlerts) events.add(new Event(Alert.PC_BACK_ONLINE, id));
            }
            return;
        }
        state.failedChecks++;
        if (pcAlerts && !state.alerted && !state.offExpected
                && state.failedChecks >= OFFLINE_AFTER_FAILED_CHECKS) {
            state.alerted = true;
            events.add(new Event(Alert.PC_OFFLINE, id));
        }
    }
}
