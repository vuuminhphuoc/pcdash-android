package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * When a background check should notify. The home phone counts as offline at once when the Tailscale
 * device list says so (the coordination server already waited before marking it offline); when the
 * list says online but the PCDash host app does not answer, one check is not enough (it can land on a
 * blip). The PC, as the home phone reports it, also needs two checks, and a PC this app was just told
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

    /** What one check found. */
    static final class Check {
        /** The home phone in the Tailscale device list; null when the list did not show it. */
        final Boolean hostListedOnline;
        /** The PCDash host app answered a status request. */
        final boolean hostAnswered;
        /** The PC as the home phone sees it; null when the home phone did not answer. */
        final Boolean pcOnline;
        final Double batteryPercent;
        final Boolean charging;

        Check(Boolean hostListedOnline, boolean hostAnswered, Boolean pcOnline, Double batteryPercent, Boolean charging) {
            this.hostListedOnline = hostListedOnline;
            this.hostAnswered = hostAnswered;
            this.pcOnline = pcOnline;
            this.batteryPercent = batteryPercent;
            this.charging = charging;
        }
    }

    /** Kept between checks (HostWatch stores it as text). */
    static final class State {
        int failedChecks;
        boolean offlineAlerted;
        boolean batteryAlerted;
        int pcFailedChecks;
        boolean pcAlerted;
        // Set when this app sent Sleep / Hibernate / Shut down; cleared once the PC is back on.
        boolean pcOffExpected;

        String encode() {
            return failedChecks + "," + bit(offlineAlerted) + "," + bit(batteryAlerted) + ","
                    + pcFailedChecks + "," + bit(pcAlerted) + "," + bit(pcOffExpected);
        }

        static State decode(String s) {
            State st = new State();
            if (s == null) return st;
            String[] parts = s.split(",");
            try {
                // 3 fields before the PC alerts existed.
                if (parts.length == 3 || parts.length == 6) {
                    st.failedChecks = Math.max(0, Integer.parseInt(parts[0]));
                    st.offlineAlerted = "1".equals(parts[1]);
                    st.batteryAlerted = "1".equals(parts[2]);
                }
                if (parts.length == 6) {
                    st.pcFailedChecks = Math.max(0, Integer.parseInt(parts[3]));
                    st.pcAlerted = "1".equals(parts[4]);
                    st.pcOffExpected = "1".equals(parts[5]);
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
    static List<Alert> apply(State state, Check check, boolean pcAlerts) {
        List<Alert> alerts = new ArrayList<Alert>();
        if (!check.hostAnswered) {
            boolean listedOffline = Boolean.FALSE.equals(check.hostListedOnline);
            state.failedChecks = listedOffline
                    ? Math.max(state.failedChecks + 1, OFFLINE_AFTER_FAILED_CHECKS)
                    : state.failedChecks + 1;
            if (state.failedChecks >= OFFLINE_AFTER_FAILED_CHECKS && !state.offlineAlerted) {
                state.offlineAlerted = true;
                alerts.add(listedOffline ? Alert.HOST_OFFLINE : Alert.HOST_NOT_ANSWERING);
            }
            // Nothing is known about the PC or the battery while the home phone is silent.
            return alerts;
        }
        state.failedChecks = 0;
        if (state.offlineAlerted) {
            state.offlineAlerted = false;
            alerts.add(Alert.HOST_BACK_ONLINE);
        }
        if (check.batteryPercent != null) {
            boolean charging = Boolean.TRUE.equals(check.charging);
            if (state.batteryAlerted) {
                if (charging || check.batteryPercent >= BATTERY_REARM_PERCENT) {
                    state.batteryAlerted = false;
                }
            } else if (!charging && check.batteryPercent <= LOW_BATTERY_PERCENT) {
                state.batteryAlerted = true;
                alerts.add(Alert.BATTERY_LOW);
            }
        }
        applyPc(state, check.pcOnline, pcAlerts, alerts);
        return alerts;
    }

    private static void applyPc(State state, Boolean pcOnline, boolean pcAlerts, List<Alert> alerts) {
        if (pcOnline == null) return;
        if (pcOnline) {
            state.pcFailedChecks = 0;
            state.pcOffExpected = false;
            if (state.pcAlerted) {
                state.pcAlerted = false;
                if (pcAlerts) alerts.add(Alert.PC_BACK_ONLINE);
            }
            return;
        }
        state.pcFailedChecks++;
        if (pcAlerts && !state.pcAlerted && !state.pcOffExpected
                && state.pcFailedChecks >= OFFLINE_AFTER_FAILED_CHECKS) {
            state.pcAlerted = true;
            alerts.add(Alert.PC_OFFLINE);
        }
    }
}
