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

    private static List<HostAlertRules.Alert> alerts(HostAlertRules.Alert... a) {
        return Arrays.asList(a);
    }

    /** Listed offline in the Tailscale device list. */
    private static HostAlertRules.Check listedOffline() {
        return new HostAlertRules.Check(false, false, null, null, null);
    }

    /** Listed online, but the host app did not answer. */
    private static HostAlertRules.Check silent() {
        return new HostAlertRules.Check(true, false, null, null, null);
    }

    private static HostAlertRules.Check up(Double battery, Boolean charging) {
        return new HostAlertRules.Check(true, true, true, battery, charging);
    }

    private static HostAlertRules.Check pc(boolean pcOnline) {
        return new HostAlertRules.Check(true, true, pcOnline, 80.0, true);
    }

    private static List<HostAlertRules.Alert> apply(HostAlertRules.State s, HostAlertRules.Check c) {
        return HostAlertRules.apply(s, c, true);
    }

    private static void testDeviceListOfflineAlertsAtOnce() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(apply(s, listedOffline()), alerts(HostAlertRules.Alert.HOST_OFFLINE), "Device list says offline: alert now");
        assertEquals(apply(s, listedOffline()), NONE, "No repeat while still offline");
        assertEquals(apply(s, up(80.0, true)), alerts(HostAlertRules.Alert.HOST_BACK_ONLINE), "Back online");
        assertEquals(apply(s, up(80.0, true)), NONE, "Quiet when all is well");
        System.out.println("PASS: testDeviceListOfflineAlertsAtOnce");
    }

    private static void testSilentHostNeedsTwoChecks() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(apply(s, silent()), NONE, "One unanswered check is not enough");
        assertEquals(apply(s, silent()), alerts(HostAlertRules.Alert.HOST_NOT_ANSWERING), "Second one alerts");
        assertEquals(apply(s, listedOffline()), NONE, "Already alerted: going offline adds nothing");
        assertEquals(apply(s, up(80.0, true)), alerts(HostAlertRules.Alert.HOST_BACK_ONLINE), "Back");

        HostAlertRules.State blip = new HostAlertRules.State();
        apply(blip, silent());
        assertEquals(apply(blip, up(90.0, true)), NONE, "One miss then fine: nothing");
        assertEquals(apply(blip, silent()), NONE, "The counter restarted");

        HostAlertRules.State unknown = new HostAlertRules.State();
        HostAlertRules.Check notListed = new HostAlertRules.Check(null, false, null, null, null);
        assertEquals(apply(unknown, notListed), NONE, "Not in the list: treated like an unanswered check");
        assertEquals(apply(unknown, notListed), alerts(HostAlertRules.Alert.HOST_NOT_ANSWERING), "Second one alerts");
        System.out.println("PASS: testSilentHostNeedsTwoChecks");
    }

    private static void testLowBatteryAlertsOnceAndRearms() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(apply(s, up(25.0, false)), NONE, "25% is fine");
        assertEquals(apply(s, up(20.0, false)), alerts(HostAlertRules.Alert.BATTERY_LOW), "20% not charging alerts");
        assertEquals(apply(s, up(15.0, false)), NONE, "Only once");
        assertEquals(apply(s, up(16.0, true)), NONE, "Charging re-arms quietly");
        assertEquals(apply(s, up(14.0, false)), alerts(HostAlertRules.Alert.BATTERY_LOW), "Unplugged again alerts again");
        assertEquals(apply(s, up(29.0, false)), NONE, "Below the re-arm level stays quiet");
        assertEquals(apply(s, up(30.0, false)), NONE, "Re-armed at 30%");
        assertEquals(apply(s, up(19.0, false)), alerts(HostAlertRules.Alert.BATTERY_LOW), "Next drop alerts");
        System.out.println("PASS: testLowBatteryAlertsOnceAndRearms");
    }

    private static void testChargingOrUnknownBatteryNeverAlerts() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(apply(s, up(5.0, true)), NONE, "Charging");
        assertEquals(apply(s, up(null, null)), NONE, "No battery data");
        System.out.println("PASS: testChargingOrUnknownBatteryNeverAlerts");
    }

    private static void testPcOfflineAndBack() {
        HostAlertRules.State s = new HostAlertRules.State();
        assertEquals(apply(s, pc(true)), NONE, "PC on");
        assertEquals(apply(s, pc(false)), NONE, "One check off is not enough");
        assertEquals(apply(s, pc(false)), alerts(HostAlertRules.Alert.PC_OFFLINE), "Second check alerts");
        assertEquals(apply(s, pc(false)), NONE, "Once");
        assertEquals(apply(s, silent()), NONE, "Home phone silent once: no alert, nothing about the PC");
        assertEquals(apply(s, pc(true)), alerts(HostAlertRules.Alert.PC_BACK_ONLINE), "PC back");
        System.out.println("PASS: testPcOfflineAndBack");
    }

    private static void testExpectedPcOffIsQuiet() {
        HostAlertRules.State s = new HostAlertRules.State();
        s.pcOffExpected = true; // this app sent Sleep
        apply(s, pc(false));
        assertEquals(apply(s, pc(false)), NONE, "Sleep sent from this app: no alert");
        assertEquals(apply(s, pc(true)), NONE, "Woken again: no 'back' alert either");
        assertEquals(s.pcOffExpected, false, "Cleared once the PC is back on");
        apply(s, pc(false));
        assertEquals(apply(s, pc(false)), alerts(HostAlertRules.Alert.PC_OFFLINE), "Next unexpected off alerts");
        System.out.println("PASS: testExpectedPcOffIsQuiet");
    }

    private static void testPcAlertsOff() {
        HostAlertRules.State s = new HostAlertRules.State();
        HostAlertRules.apply(s, pc(false), false);
        assertEquals(HostAlertRules.apply(s, pc(false), false), NONE, "PC alerts off");
        assertEquals(HostAlertRules.apply(s, listedOffline(), false), alerts(HostAlertRules.Alert.HOST_OFFLINE), "Host alerts still work");
        System.out.println("PASS: testPcAlertsOff");
    }

    private static void testStateRoundTrip() {
        HostAlertRules.State s = new HostAlertRules.State();
        s.failedChecks = 3;
        s.offlineAlerted = true;
        s.pcFailedChecks = 2;
        s.pcOffExpected = true;
        HostAlertRules.State back = HostAlertRules.State.decode(s.encode());
        assertEquals(back.failedChecks, 3, "failedChecks");
        assertEquals(back.offlineAlerted, true, "offlineAlerted");
        assertEquals(back.batteryAlerted, false, "batteryAlerted");
        assertEquals(back.pcFailedChecks, 2, "pcFailedChecks");
        assertEquals(back.pcAlerted, false, "pcAlerted");
        assertEquals(back.pcOffExpected, true, "pcOffExpected");
        HostAlertRules.State old = HostAlertRules.State.decode("1,1,0");
        assertEquals(old.offlineAlerted, true, "State saved by the previous version still reads");
        assertEquals(old.pcFailedChecks, 0, "with fresh PC fields");
        assertEquals(HostAlertRules.State.decode("garbage").failedChecks, 0, "Bad text starts fresh");
        assertEquals(HostAlertRules.State.decode(null).offlineAlerted, false, "No text starts fresh");
        System.out.println("PASS: testStateRoundTrip");
    }

    public static void main(String[] args) {
        System.out.println("Running HostAlertRulesTest...");
        testDeviceListOfflineAlertsAtOnce();
        testSilentHostNeedsTwoChecks();
        testLowBatteryAlertsOnceAndRearms();
        testChargingOrUnknownBatteryNeverAlerts();
        testPcOfflineAndBack();
        testExpectedPcOffIsQuiet();
        testPcAlertsOff();
        testStateRoundTrip();
        System.out.println("ALL HostAlertRulesTest PASSED");
    }
}
