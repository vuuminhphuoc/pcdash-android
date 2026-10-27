package vn.vuumi.pcdash.remote;

import java.util.concurrent.atomic.AtomicReference;

public class TabNavigatorTest {

    public static void main(String[] args) {
        System.out.println("Running TabNavigatorTest...");
        testDefaultTabIsControl();
        testSelectQuota();
        testSelectMoreReturnsFalseAndKeepsCurrent();
        testReselectCurrentReturnsFalse();
        testOnBackFromNonControlReturnsTrueAndSetsControl();
        testOnBackAtControlReturnsFalse();
        testListenerCallbacks();
        System.out.println("ALL TabNavigatorTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testDefaultTabIsControl() {
        TabNavigator nav = new TabNavigator();
        assertTrue(nav.current() == MainTab.CONTROL, "Default tab must be CONTROL");
        System.out.println("PASS: testDefaultTabIsControl");
    }

    public static void testSelectQuota() {
        TabNavigator nav = new TabNavigator();
        boolean changed = nav.select(MainTab.QUOTA);
        assertTrue(changed, "select(QUOTA) must return true");
        assertTrue(nav.current() == MainTab.QUOTA, "current() must be QUOTA");
        System.out.println("PASS: testSelectQuota");
    }

    public static void testSelectMoreReturnsFalseAndKeepsCurrent() {
        TabNavigator nav = new TabNavigator();
        nav.select(MainTab.QUOTA);
        boolean changed = nav.select(MainTab.MORE);
        assertTrue(!changed, "select(MORE) must return false");
        assertTrue(nav.current() == MainTab.QUOTA, "current() must remain QUOTA after select(MORE)");
        System.out.println("PASS: testSelectMoreReturnsFalseAndKeepsCurrent");
    }

    public static void testReselectCurrentReturnsFalse() {
        TabNavigator nav = new TabNavigator();
        nav.select(MainTab.QUOTA);
        boolean changed = nav.select(MainTab.QUOTA);
        assertTrue(!changed, "Reselecting current tab must return false");
        assertTrue(nav.current() == MainTab.QUOTA, "current() must still be QUOTA");
        System.out.println("PASS: testReselectCurrentReturnsFalse");
    }

    public static void testOnBackFromNonControlReturnsTrueAndSetsControl() {
        TabNavigator nav = new TabNavigator();
        nav.select(MainTab.LOG);
        assertTrue(nav.current() == MainTab.LOG, "current() must be LOG");
        boolean handled = nav.onBack();
        assertTrue(handled, "onBack() from LOG must return true");
        assertTrue(nav.current() == MainTab.CONTROL, "current() must be CONTROL after onBack()");
        System.out.println("PASS: testOnBackFromNonControlReturnsTrueAndSetsControl");
    }

    public static void testOnBackAtControlReturnsFalse() {
        TabNavigator nav = new TabNavigator();
        assertTrue(nav.current() == MainTab.CONTROL, "current() is CONTROL");
        boolean handled = nav.onBack();
        assertTrue(!handled, "onBack() at CONTROL must return false");
        assertTrue(nav.current() == MainTab.CONTROL, "current() remains CONTROL");
        System.out.println("PASS: testOnBackAtControlReturnsFalse");
    }

    public static void testListenerCallbacks() {
        final AtomicReference<MainTab> lastTab = new AtomicReference<>(null);
        TabNavigator nav = new TabNavigator(new TabNavigator.Listener() {
            @Override
            public void onTabSelected(MainTab tab) {
                lastTab.set(tab);
            }
        });

        nav.select(MainTab.MONITOR);
        assertTrue(lastTab.get() == MainTab.MONITOR, "Listener must receive MONITOR");

        lastTab.set(null);
        nav.select(MainTab.MORE);
        assertTrue(lastTab.get() == null, "select(MORE) must not trigger listener");

        nav.select(MainTab.MONITOR);
        assertTrue(lastTab.get() == null, "Reselecting MONITOR must not trigger listener");

        nav.onBack();
        assertTrue(lastTab.get() == MainTab.CONTROL, "onBack() must trigger listener with CONTROL");
        System.out.println("PASS: testListenerCallbacks");
    }
}
