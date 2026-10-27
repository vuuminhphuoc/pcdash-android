package vn.vuumi.pcdash.remote;

import java.util.ArrayList;
import java.util.List;

/**
 * When a background check should notify. One failed check is not enough to call the home phone
 * offline (a check can land on a blip); each alert fires once and re-arms when the problem clears.
 */
final class HostAlertRules {
    static final int OFFLINE_AFTER_FAILED_CHECKS = 2;
    static final double LOW_BATTERY_PERCENT = 20.0;
    // Charging, or back to this level, clears a low-battery alert so the next drop alerts again.
    static final double BATTERY_REARM_PERCENT = 30.0;

    enum Alert {
        HOST_OFFLINE,
        HOST_BACK_ONLINE,
        BATTERY_LOW
    }

    /** What one check found. */
    static final class Check {
        final boolean reachable;
        final Double batteryPercent;
        final Boolean charging;

        Check(boolean reachable, Double batteryPercent, Boolean charging) {
            this.reachable = reachable;
            this.batteryPercent = batteryPercent;
            this.charging = charging;
        }
    }

    /** Kept between checks (HostWatch stores it as text). */
    static final class State {
        int failedChecks;
        boolean offlineAlerted;
        boolean batteryAlerted;

        String encode() {
            return failedChecks + "," + (offlineAlerted ? 1 : 0) + "," + (batteryAlerted ? 1 : 0);
        }

        static State decode(String s) {
            State st = new State();
            if (s == null) return st;
            String[] parts = s.split(",");
            try {
                if (parts.length == 3) {
                    st.failedChecks = Math.max(0, Integer.parseInt(parts[0]));
                    st.offlineAlerted = "1".equals(parts[1]);
                    st.batteryAlerted = "1".equals(parts[2]);
                }
            } catch (NumberFormatException ignored) {
                return new State();
            }
            return st;
        }
    }

    private HostAlertRules() {}

    /** Updates the state with one check and returns the notifications to post. */
    static List<Alert> apply(State state, Check check) {
        List<Alert> alerts = new ArrayList<Alert>();
        if (!check.reachable) {
            state.failedChecks++;
            if (state.failedChecks >= OFFLINE_AFTER_FAILED_CHECKS && !state.offlineAlerted) {
                state.offlineAlerted = true;
                alerts.add(Alert.HOST_OFFLINE);
            }
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
        return alerts;
    }
}
