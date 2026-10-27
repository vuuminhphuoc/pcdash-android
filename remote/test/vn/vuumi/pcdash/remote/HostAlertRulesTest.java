package vn.vuumi.pcdash.remote;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class HostAlertRulesTest {

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static final List<HostAlertRules.Alert> NONE = Collections.<HostAlertRules.Alert>emptyList();

    private static HostAlertRules.Check down() {
        return new HostAlertRules.Check(false, null, null);
    }

    private static HostAlertRules.Check up(Double battery, Boolean charging) {
        return new HostAlertRules.Check(true, battery, charging);
    }

    private static void testOfflineNeedsTwoFailedChecksAndAlertsOnce() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(HostAlertRules.apply(s, down()), NONE, "One failed check is not enough");
        assertEquals(HostAlertRules.apply(s, down()), Arrays.asList(HostAlertRules.Alert.HOST_OFFLINE), "Second failed check alerts");
        assertEquals(HostAlertRules.apply(s, down()), NONE, "No repeat while still offline");
        assertEquals(HostAlertRules.apply(s, up(80.0, true)), Arrays.asList(HostAlertRules.Alert.HOST_BACK_ONLINE), "Back online");
        assertEquals(HostAlertRules.apply(s, up(80.0, true)), NONE, "Quiet when all is well");
        System.out.println("PASS: testOfflineNeedsTwoFailedChecksAndAlertsOnce");
    }

    private static void testBlipDoesNotAlert() {
        HostAlertRules.State s = new HostAlertRules.State();
        HostAlertRules.apply(s, down());
        assertEquals(HostAlertRules.apply(s, up(90.0, true)), NONE, "One miss then fine: nothing");
        assertEquals(HostAlertRules.apply(s, down()), NONE, "The counter restarted");
        System.out.println("PASS: testBlipDoesNotAlert");
    }

    private static void testLowBatteryAlertsOnceAndRearms() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(HostAlertRules.apply(s, up(25.0, false)), NONE, "25% is fine");
        assertEquals(HostAlertRules.apply(s, up(20.0, false)), Arrays.asList(HostAlertRules.Alert.BATTERY_LOW), "20% not charging alerts");
        assertEquals(HostAlertRules.apply(s, up(15.0, false)), NONE, "Only once");
        assertEquals(HostAlertRules.apply(s, up(16.0, true)), NONE, "Charging re-arms quietly");
        assertEquals(HostAlertRules.apply(s, up(14.0, false)), Arrays.asList(HostAlertRules.Alert.BATTERY_LOW), "Unplugged again alerts again");
        assertEquals(HostAlertRules.apply(s, up(29.0, false)), NONE, "Below the re-arm level stays quiet");
        assertEquals(HostAlertRules.apply(s, up(30.0, false)), NONE, "Re-armed at 30%");
        assertEquals(HostAlertRules.apply(s, up(19.0, false)), Arrays.asList(HostAlertRules.Alert.BATTERY_LOW), "Next drop alerts");
        System.out.println("PASS: testLowBatteryAlertsOnceAndRearms");
    }

    private static void testChargingOrUnknownBatteryNeverAlerts() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(HostAlertRules.apply(s, up(5.0, true)), NONE, "Charging");
        assertEquals(HostAlertRules.apply(s, up(null, null)), NONE, "No battery data");
        System.out.println("PASS: testChargingOrUnknownBatteryNeverAlerts");
    }

    private static void testStateRoundTrip() {
        HostAlertRules.State s = new HostAlertRules.State();
        s.failedChecks = 3;
        s.offlineAlerted = true;
        HostAlertRules.State back = HostAlertRules.State.decode(s.encode());
        assertEquals(back.failedChecks, 3, "failedChecks");
        assertEquals(back.offlineAlerted, true, "offlineAlerted");
        assertEquals(back.batteryAlerted, false, "batteryAlerted");
        assertEquals(HostAlertRules.State.decode("garbage").failedChecks, 0, "Bad text starts fresh");
        assertEquals(HostAlertRules.State.decode(null).offlineAlerted, false, "No text starts fresh");
        System.out.println("PASS: testStateRoundTrip");
    }

    public static void main(String[] args) {
        System.out.println("Running HostAlertRulesTest...");
        testOfflineNeedsTwoFailedChecksAndAlertsOnce();
        testBlipDoesNotAlert();
        testLowBatteryAlertsOnceAndRearms();
        testChargingOrUnknownBatteryNeverAlerts();
        testStateRoundTrip();
        System.out.println("ALL HostAlertRulesTest PASSED");
    }
}
