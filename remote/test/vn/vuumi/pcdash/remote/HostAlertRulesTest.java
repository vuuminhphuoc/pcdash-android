package vn.vuumi.pcdash.remote;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HostAlertRulesTest {

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    private static final List<HostAlertRules.Event> NONE = Collections.<HostAlertRules.Event>emptyList();

    // Alerts about the home phone (no PC).
    private static List<HostAlertRules.Event> alerts(HostAlertRules.Alert... a) {
        HostAlertRules.Event[] out = new HostAlertRules.Event[a.length];
        for (int i = 0; i < a.length; i++) {
            out[i] = new HostAlertRules.Event(a[i], a[i] == HostAlertRules.Alert.PC_OFFLINE || a[i] == HostAlertRules.Alert.PC_BACK_ONLINE ? "" : null);
        }
        return Arrays.asList(out);
    }

    private static HostAlertRules.Event ev(HostAlertRules.Alert a, String pc) {
        return new HostAlertRules.Event(a, pc);
    }

    // The single PC of a host without a PC list, reported under "".
    private static Map<String, Boolean> onePc(boolean online) {
        return Collections.singletonMap("", online);
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
        return new HostAlertRules.Check(true, true, onePc(true), battery, charging);
    }

    private static HostAlertRules.Check pc(boolean pcOnline) {
        return new HostAlertRules.Check(true, true, onePc(pcOnline), 80.0, true);
    }

    private static List<HostAlertRules.Event> apply(HostAlertRules.State s, HostAlertRules.Check c) {
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
        s.pc("").offExpected = true; // this app sent Sleep
        apply(s, pc(false));
        assertEquals(apply(s, pc(false)), NONE, "Sleep sent from this app: no alert");
        assertEquals(apply(s, pc(true)), NONE, "Woken again: no 'back' alert either");
        assertEquals(s.pc("").offExpected, false, "Cleared once the PC is back on");
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

    private static void testSeveralPcs() {
        HostAlertRules.State s = new HostAlertRules.State();
        Map<String, Boolean> both = new LinkedHashMap<String, Boolean>();
        both.put("pc1", true);
        both.put("pcb2", false);
        HostAlertRules.Check c = new HostAlertRules.Check(true, true, both, 80.0, true);
        assertEquals(apply(s, c), NONE, "First miss");
        assertEquals(apply(s, c), Arrays.asList(ev(HostAlertRules.Alert.PC_OFFLINE, "pcb2")), "Only the PC that is off");
        s.pc("pc1").offExpected = true;
        Map<String, Boolean> flipped = new LinkedHashMap<String, Boolean>();
        flipped.put("pc1", false);
        flipped.put("pcb2", true);
        HostAlertRules.Check f = new HostAlertRules.Check(true, true, flipped, 80.0, true);
        assertEquals(apply(s, f), Arrays.asList(ev(HostAlertRules.Alert.PC_BACK_ONLINE, "pcb2")), "pcb2 back");
        assertEquals(apply(s, f), NONE, "pc1 was put to sleep from this app: quiet");
        Map<String, Boolean> onlyB = Collections.singletonMap("pcb2", true);
        apply(s, new HostAlertRules.Check(true, true, onlyB, 80.0, true));
        assertEquals(s.pcs.containsKey("pc1"), false, "A PC removed on the home phone is forgotten");
        System.out.println("PASS: testSeveralPcs");
    }

    private static void testStateRoundTrip() {
        HostAlertRules.State s = new HostAlertRules.State();
        s.failedChecks = 3;
        s.offlineAlerted = true;
        s.pc("pc1").failedChecks = 2;
        s.pc("pc1").offExpected = true;
        s.pc("pcb2").alerted = true;
        HostAlertRules.State back = HostAlertRules.State.decode(s.encode());
        assertEquals(back.failedChecks, 3, "failedChecks");
        assertEquals(back.offlineAlerted, true, "offlineAlerted");
        assertEquals(back.batteryAlerted, false, "batteryAlerted");
        assertEquals(back.pc("pc1").failedChecks, 2, "pc1 failedChecks");
        assertEquals(back.pc("pc1").offExpected, true, "pc1 offExpected");
        assertEquals(back.pc("pcb2").alerted, true, "pcb2 alerted");
        HostAlertRules.State old = HostAlertRules.State.decode("1,1,0");
        assertEquals(old.offlineAlerted, true, "State saved two versions ago still reads");
        HostAlertRules.State oneLine = HostAlertRules.State.decode("0,0,0,2,1,0");
        assertEquals(oneLine.pc("").failedChecks, 2, "The previous version's single PC");
        assertEquals(oneLine.pc("").alerted, true, "with its alert");
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
        testSeveralPcs();
        testStateRoundTrip();
        System.out.println("ALL HostAlertRulesTest PASSED");
    }
}
