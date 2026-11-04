package vn.vuumi.pcdash;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Finding a PC again after the router gave it a new address (see PcLocator and PcRecovery). */
public class PcLocatorTest {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String OTHER_TOKEN = "ffffffffffffffffffffffffffffffff";

    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError("Assertion failed: " + message);
    }

    private static void assertEquals(Object actual, Object expected, String message) {
        if (expected == null && actual == null) return;
        if (expected != null && expected.equals(actual)) return;
        throw new AssertionError("Assertion failed for " + message + ": expected <" + expected + "> but got <" + actual + ">");
    }

    /** A LAN: which addresses listen on the agent port and what each answers to a proof request. */
    private static class FakeNet implements PcLocator.Net {
        final Map<String, String> tokenAt = new HashMap<String, String>();   // address -> token its agent holds
        final Map<String, String> plainHttp = new HashMap<String, String>(); // address -> answers HTTP, no proof route
        final List<String> probed = Collections.synchronizedList(new ArrayList<String>());
        PcLocator.Subnet subnet = new PcLocator.Subnet("192.168.1.50", 24);

        @Override
        public boolean isOpen(String ip, int port, int timeoutMs) {
            probed.add(ip);
            return tokenAt.containsKey(ip) || plainHttp.containsKey(ip);
        }

        @Override
        public String whoami(String ip, int port, String challenge, int timeoutMs) {
            if (plainHttp.containsKey(ip)) return PcLocator.UNSUPPORTED;
            String token = tokenAt.get(ip);
            if (token == null) return null;
            return AgentAuth.signature(token, "whoami", challenge, "GET", "/whoami", null);
        }

        @Override
        public PcLocator.Subnet localSubnet(String nearIp) {
            return subnet;
        }
    }

    private static void testFindsTheAgentWhoseProofIsRight() {
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.201", TOKEN);
        String found = PcLocator.find(TOKEN, 8799, net.subnet, "192.168.1.10", net, 16, 10000);
        assertEquals(found, "192.168.1.201", "The PC's new address");
        System.out.println("PASS: testFindsTheAgentWhoseProofIsRight");
    }

    private static void testAnotherDeviceOnThePortIsNotTheAgent() {
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.20", OTHER_TOKEN);  // an agent of someone else, or an impostor
        net.plainHttp.put("192.168.1.30", "web");      // a printer's web page on the same port
        net.tokenAt.put("192.168.1.201", TOKEN);
        String found = PcLocator.find(TOKEN, 8799, net.subnet, "192.168.1.10", net, 16, 10000);
        assertEquals(found, "192.168.1.201", "Only the device with the token counts");

        FakeNet onlyImpostor = new FakeNet();
        onlyImpostor.tokenAt.put("192.168.1.20", OTHER_TOKEN);
        assertEquals(PcLocator.find(TOKEN, 8799, onlyImpostor.subnet, "192.168.1.10", onlyImpostor, 16, 10000), null,
                "A device without the token is never adopted");
        System.out.println("PASS: testAnotherDeviceOnThePortIsNotTheAgent");
    }

    private static void testNothingFoundWhenThePcIsOff() {
        FakeNet net = new FakeNet();
        assertEquals(PcLocator.find(TOKEN, 8799, net.subnet, "192.168.1.10", net, 16, 10000), null, "PC off");
        assertEquals(PcLocator.find("", 8799, net.subnet, null, net, 16, 10000), null, "No token, no search");
        System.out.println("PASS: testNothingFoundWhenThePcIsOff");
    }

    private static void testSkipsThePhoneAndTheOldAddressAndStaysInTheSubnet() {
        FakeNet net = new FakeNet();
        PcLocator.find(TOKEN, 8799, net.subnet, "192.168.1.10", net, 16, 10000);
        assertTrue(!net.probed.contains("192.168.1.50"), "Not the phone itself");
        assertTrue(!net.probed.contains("192.168.1.10"), "Not the old address, it was just tried");
        assertTrue(!net.probed.contains("192.168.1.0") && !net.probed.contains("192.168.1.255"), "Not network or broadcast");
        assertEquals(net.probed.size(), 252, "Every other address of a /24 once");

        List<String> wide = PcLocator.candidates(new PcLocator.Subnet("10.1.2.3", 8), null);
        assertEquals(wide.size(), 1021, "A wide network is cut down to a /22 (1022 hosts, minus the phone)");
        assertTrue(wide.contains("10.1.0.1") && wide.contains("10.1.3.254") && !wide.contains("10.1.4.1"),
                "The /22 around the phone's address");
        System.out.println("PASS: testSkipsThePhoneAndTheOldAddressAndStaysInTheSubnet");
    }

    private static void testProofCheck() {
        String challenge = PcLocator.newChallenge();
        assertEquals(challenge.length(), 32, "Challenge is 32 hex characters");
        assertTrue(!challenge.equals(PcLocator.newChallenge()), "Challenges differ");
        String good = AgentAuth.signature(TOKEN, "whoami", challenge, "GET", "/whoami", null);
        assertTrue(PcLocator.proves(TOKEN, challenge, good), "Right proof");
        assertTrue(PcLocator.proves(TOKEN, challenge, good.toUpperCase()), "Hex case does not matter");
        assertTrue(!PcLocator.proves(OTHER_TOKEN, challenge, good), "Other token");
        assertTrue(!PcLocator.proves(TOKEN, "x" + challenge, good), "Other challenge");
        assertTrue(!PcLocator.proves(TOKEN, challenge, null) && !PcLocator.proves(TOKEN, challenge, PcLocator.UNSUPPORTED), "No proof");
        assertTrue(!PcLocator.proves("", challenge, good), "No token");
        System.out.println("PASS: testProofCheck");
    }

    // ---- PcRecovery ----

    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable r) {
            r.run();
        }
    };

    private static final class Clock implements TimeSource {
        long now = 1_000_000L;

        @Override
        public long nowEpochMs() {
            return now;
        }
    }

    private static final class Moves implements PcRecovery.Listener {
        final List<String> log = new ArrayList<String>();

        @Override
        public void onAddressChanged(String pcName, String oldIp, String newIp) {
            log.add(pcName + ":" + oldIp + "->" + newIp);
        }
    }

    private static PcSettings settingsWithPc(String ip) {
        PcSettings s = new PcSettings(new FakeSharedPreferences(), new FakeSecretCipher());
        s.save(ip, "AA:BB:CC:DD:EE:FF", 8799, TOKEN);
        return s;
    }

    private static void testSavesTheNewAddressAfterTwoMissedSamples() {
        PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.201", TOKEN);
        Moves moves = new Moves();
        Clock clock = new Clock();
        PcRecovery recovery = new PcRecovery(settings, net, moves, clock, DIRECT);

        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.10", "One missed sample is not enough");
        clock.now += 30000;
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.201", "Address saved");
        assertEquals(moves.log.size(), 1, "Told once");
        assertEquals(moves.log.get(0), "PC:192.168.1.10->192.168.1.201", "What was told");
        assertEquals(settings.findPc(null).mac, "AA:BB:CC:DD:EE:FF", "MAC untouched");
        assertEquals(settings.findPc(null).token, TOKEN, "Token untouched");
        System.out.println("PASS: testSavesTheNewAddressAfterTwoMissedSamples");
    }

    private static void testNetworkChangeSearchesAtOnce() {
        PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.201", TOKEN);
        Clock clock = new Clock();
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), clock, DIRECT);
        clock.now += 1000;
        recovery.networkChanged();
        clock.now += 1000;
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.201", "Found at the first missed sample after the network came back");
        System.out.println("PASS: testNetworkChangeSearchesAtOnce");
    }

    private static void testSearchesAreSpacedOut() {
        PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet();
        Clock clock = new Clock();
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), clock, DIRECT);
        recovery.onUnreachable(settings.findPc(null));
        recovery.onUnreachable(settings.findPc(null)); // search 1, PC is off
        int after = net.probed.size();
        assertTrue(after > 200, "The first search scanned the subnet");
        clock.now += 30000;
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(net.probed.size(), after, "No second search 30 seconds later");
        clock.now += PcRecovery.RESEARCH_MS;
        net.tokenAt.put("192.168.1.99", TOKEN); // the PC is on now
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.99", "Searched again after the wait and found it");
        System.out.println("PASS: testSearchesAreSpacedOut");
    }

    private static void testReachablePcResetsTheCount() {
        PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.201", TOKEN);
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), new Clock(), DIRECT);
        recovery.onUnreachable(settings.findPc(null));
        recovery.onReachable(PcSettings.FIRST_PC_ID);
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.10", "A good sample in between starts the count again");
        System.out.println("PASS: testReachablePcResetsTheCount");
    }

    private static void testLeavesAnAgentThatAnswersItsOwnAddress() {
        PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.10", TOKEN);  // only slow
        net.tokenAt.put("192.168.1.201", TOKEN);
        Clock clock = new Clock();
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), clock, DIRECT);
        recovery.onUnreachable(settings.findPc(null));
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.10", "It answers where it was: nothing to move");
        System.out.println("PASS: testLeavesAnAgentThatAnswersItsOwnAddress");
    }

    private static void testOldAgentIsNotChased() {
        PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet();
        net.plainHttp.put("192.168.1.10", "agent without the proof route");
        net.tokenAt.put("192.168.1.201", TOKEN);
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), new Clock(), DIRECT);
        recovery.onUnreachable(settings.findPc(null));
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.10", "An agent that cannot prove itself is left where it is");
        System.out.println("PASS: testOldAgentIsNotChased");
    }

    private static void testAddressEditedMeanwhileIsKept() {
        final PcSettings settings = settingsWithPc("192.168.1.10");
        FakeNet net = new FakeNet() {
            @Override
            public PcLocator.Subnet localSubnet(String nearIp) {
                settings.save("192.168.1.77", "AA:BB:CC:DD:EE:FF", 8799, TOKEN); // the person edits during the search
                return super.localSubnet(nearIp);
            }
        };
        net.tokenAt.put("192.168.1.201", TOKEN);
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), new Clock(), DIRECT);
        recovery.onUnreachable(settings.findPc(null));
        recovery.onUnreachable(settings.findPc(null));
        assertEquals(settings.getPcIp(), "192.168.1.77", "The person's edit wins");
        System.out.println("PASS: testAddressEditedMeanwhileIsKept");
    }

    private static void testSecondPcMovesWithoutTouchingTheFirst() {
        PcSettings settings = new PcSettings(new FakeSharedPreferences(), new FakeSecretCipher());
        settings.addPc("Desk", "192.168.1.10", "AA:BB:CC:DD:EE:01", 8799, OTHER_TOKEN);
        String second = settings.addPc("Gaming", "192.168.1.11", "AA:BB:CC:DD:EE:02", 8799, TOKEN);
        FakeNet net = new FakeNet();
        net.tokenAt.put("192.168.1.202", TOKEN);
        PcRecovery recovery = new PcRecovery(settings, net, new Moves(), new Clock(), DIRECT);
        PcProfile gaming = settings.findPc(second);
        recovery.onUnreachable(gaming);
        recovery.onUnreachable(gaming);
        assertEquals(settings.findPc(second).ip, "192.168.1.202", "The second PC moved");
        assertEquals(settings.findPc(null).ip, "192.168.1.10", "The first PC stayed");
        System.out.println("PASS: testSecondPcMovesWithoutTouchingTheFirst");
    }

    public static void main(String[] args) {
        testFindsTheAgentWhoseProofIsRight();
        testAnotherDeviceOnThePortIsNotTheAgent();
        testNothingFoundWhenThePcIsOff();
        testSkipsThePhoneAndTheOldAddressAndStaysInTheSubnet();
        testProofCheck();
        testSavesTheNewAddressAfterTwoMissedSamples();
        testNetworkChangeSearchesAtOnce();
        testSearchesAreSpacedOut();
        testReachablePcResetsTheCount();
        testLeavesAnAgentThatAnswersItsOwnAddress();
        testOldAgentIsNotChased();
        testAddressEditedMeanwhileIsKept();
        testSecondPcMovesWithoutTouchingTheFirst();
        System.out.println("ALL PcLocatorTest PASSED");
    }
}
