package vn.vuumi.pcdash.remote;

public class ActionGuardTest {

    public static void main(String[] args) {
        System.out.println("Running ActionGuardTest...");
        testIsDangerousAction();
        testAllActionCombinations();
        testForgetHostRequirement();
        System.out.println("ALL ActionGuardTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testForgetHostRequirement() {
        assertTrue(ActionGuard.checkForgetHostRequirement(true) == ActionGuard.AuthRequirement.DEVICE_CREDENTIAL, "forget host needs credential when secure");
        assertTrue(ActionGuard.checkForgetHostRequirement(false) == ActionGuard.AuthRequirement.CONFIRM_DIALOG, "forget host falls back to confirm dialog");
        System.out.println("PASS: testForgetHostRequirement");
    }

    public static void testIsDangerousAction() {
        assertTrue(ActionGuard.isDangerousAction("hibernate"), "hibernate is dangerous");
        assertTrue(ActionGuard.isDangerousAction("shutdown"), "shutdown is dangerous");
        assertTrue(ActionGuard.isDangerousAction("HIBERNATE"), "case-insensitive HIBERNATE");
        assertTrue(ActionGuard.isDangerousAction("  shutdown  "), "trimmed shutdown");

        assertTrue(!ActionGuard.isDangerousAction("wake"), "wake not dangerous");
        assertTrue(!ActionGuard.isDangerousAction("sleep"), "sleep not dangerous");
        assertTrue(!ActionGuard.isDangerousAction("abort"), "abort not dangerous");
        assertTrue(ActionGuard.isDangerousAction("restart"), "restart is dangerous");
        assertTrue(ActionGuard.isDangerousAction("logoff"), "sign out is dangerous");
        assertTrue(!ActionGuard.isDangerousAction("lock"), "lock not dangerous");
        assertTrue(!ActionGuard.isDangerousAction("reboot"), "reboot is not a command");
        assertTrue(!ActionGuard.isDangerousAction(""), "empty not dangerous");
        assertTrue(!ActionGuard.isDangerousAction(null), "null not dangerous");
        System.out.println("PASS: testIsDangerousAction");
    }

    public static void testAllActionCombinations() {
        String[] safeActions = new String[]{"wake", "sleep", "lock", "abort", "unknown", "", null};
        boolean[] flags = new boolean[]{true, false};

        // All safe actions: always NONE regardless of switch or device security
        for (String action : safeActions) {
            for (boolean authSwitch : flags) {
                for (boolean secureDevice : flags) {
                    ActionGuard.AuthRequirement req = ActionGuard.checkAuthRequirement(action, authSwitch, secureDevice);
                    assertTrue(req == ActionGuard.AuthRequirement.NONE,
                            "Safe action " + action + " (switch=" + authSwitch + ", secure=" + secureDevice + ") must be NONE");
                }
            }
        }

        // Dangerous actions: hibernate & shutdown
        String[] dangerousActions = new String[]{"hibernate", "shutdown", "restart", "logoff", "HIBERNATE", "SHUTDOWN"};
        for (String action : dangerousActions) {
            // Switch ON + Secure Device -> DEVICE_CREDENTIAL
            ActionGuard.AuthRequirement req1 = ActionGuard.checkAuthRequirement(action, true, true);
            assertTrue(req1 == ActionGuard.AuthRequirement.DEVICE_CREDENTIAL,
                    action + " (switch=true, secure=true) must be DEVICE_CREDENTIAL");

            // Switch ON + Insecure Device -> CONFIRM_DIALOG
            ActionGuard.AuthRequirement req2 = ActionGuard.checkAuthRequirement(action, true, false);
            assertTrue(req2 == ActionGuard.AuthRequirement.CONFIRM_DIALOG,
                    action + " (switch=true, secure=false) must be CONFIRM_DIALOG");

            // Switch OFF + Secure Device -> NONE
            ActionGuard.AuthRequirement req3 = ActionGuard.checkAuthRequirement(action, false, true);
            assertTrue(req3 == ActionGuard.AuthRequirement.NONE,
                    action + " (switch=false, secure=true) must be NONE");

            // Switch OFF + Insecure Device -> NONE
            ActionGuard.AuthRequirement req4 = ActionGuard.checkAuthRequirement(action, false, false);
            assertTrue(req4 == ActionGuard.AuthRequirement.NONE,
                    action + " (switch=false, secure=false) must be NONE");
        }

        System.out.println("PASS: testAllActionCombinations");
    }
}
