package vn.vuumi.pcdash.remote;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import vn.vuumi.pcdash.net.PcnetBridge;
public class UiThreadSafetyTest {

    private static final Set<String> ALLOWLIST_SYNCHRONIZED = new HashSet<String>(Arrays.asList(
            "session", "open", "pair", "close", "forgetHost"
    ));

    public static void main(String[] args) throws Exception {
        System.out.println("Running UiThreadSafetyTest...");
        testReflectionRules();
        testBehavioralNetworkCallDoesNotBlockUiThread();
        testBehavioralOpenDoesNotBlockUiThread();
        System.out.println("ALL UiThreadSafetyTest assertions PASSED!");
    }

    public static void testReflectionRules() throws Exception {
        Class<?> clazz = RemoteController.class;
        Method[] methods = clazz.getDeclaredMethods();

        for (Method m : methods) {
            String name = m.getName();
            boolean isSync = (m.getModifiers() & Modifier.SYNCHRONIZED) != 0;

            if (isSync) {
                if (!ALLOWLIST_SYNCHRONIZED.contains(name)) {
                    throw new AssertionError("Method '" + name + "' is declared synchronized but is NOT in allowlist: "
                            + ALLOWLIST_SYNCHRONIZED);
                }
            }

            if (Modifier.isPublic(m.getModifiers())) {
                if (name.startsWith("get") || name.startsWith("post") || name.startsWith("download") || name.startsWith("forget")) {
                    if (!ALLOWLIST_SYNCHRONIZED.contains(name) && isSync) {
                        throw new AssertionError("Public I/O method '" + name + "' must NOT be declared synchronized");
                    }
                }
            }
        }

        // Specific assertions for isPaired, getBoundLocalPort, getBridge
        Method isPaired = clazz.getMethod("isPaired");
        if ((isPaired.getModifiers() & Modifier.SYNCHRONIZED) != 0) {
            throw new AssertionError("isPaired() must NOT be synchronized");
        }

        Method getBoundLocalPort = clazz.getMethod("getBoundLocalPort");
        if ((getBoundLocalPort.getModifiers() & Modifier.SYNCHRONIZED) != 0) {
            throw new AssertionError("getBoundLocalPort() must NOT be synchronized");
        }

        Method getBridge = clazz.getMethod("getBridge");
        if ((getBridge.getModifiers() & Modifier.SYNCHRONIZED) != 0) {
            throw new AssertionError("getBridge() must NOT be synchronized");
        }

        System.out.println("  PASS: testReflectionRules");
    }

    public static void testBehavioralNetworkCallDoesNotBlockUiThread() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_uithread_net_" + System.currentTimeMillis());
        tempDir.mkdirs();
        try {
            RemoteConfigStore store = new RemoteConfigStore(new File(tempDir, "config.json"));
            store.save(new RemoteConfigStore.RemoteConfig("tok_test", "100.64.0.1", 8091));

            FakePcnetBridge bridge = new FakePcnetBridge();
            bridge.localPort = 12345;

            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);

            RemoteApiClient slowApi = new RemoteApiClient() {
                @Override
                public String getMetrics(int localPort, String token) throws IOException {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}
                    return "{}";
                }
            };

            final RemoteController controller = new RemoteController(new PairingClient(), bridge, store, slowApi, new File(tempDir, "tsnet"));
            controller.open();

            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        controller.getMetrics();
                    } catch (IOException ignored) {}
                }
            });
            worker.start();

            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Network call never started");
            }

            // UI thread calls must complete in under 200ms
            final AtomicBoolean success = new AtomicBoolean(false);
            final long[] elapsed = new long[1];

            Thread uiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    long start = System.currentTimeMillis();
                    controller.isPaired();
                    controller.getBoundLocalPort();
                    controller.getBridge();
                    elapsed[0] = System.currentTimeMillis() - start;
                    success.set(true);
                }
            });

            uiThread.start();
            uiThread.join(500);

            release.countDown();
            worker.join(5000);

            if (!success.get()) {
                throw new AssertionError("UI thread calls blocked while network call was in flight!");
            }
            if (elapsed[0] >= 200) {
                throw new AssertionError("UI thread calls took " + elapsed[0] + " ms (must be < 200 ms)");
            }

            controller.close();
            System.out.println("  PASS: testBehavioralNetworkCallDoesNotBlockUiThread (" + elapsed[0] + " ms)");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    public static void testBehavioralOpenDoesNotBlockUiThread() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pcdash_uithread_open_" + System.currentTimeMillis());
        tempDir.mkdirs();
        try {
            RemoteConfigStore store = new RemoteConfigStore(new File(tempDir, "config.json"));
            store.save(new RemoteConfigStore.RemoteConfig("tok_test", "100.64.0.1", 8091));

            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);

            PcnetBridge slowBridge = new PcnetBridge() {
                @Override
                public boolean isRunning() {
                    return false;
                }

                @Override
                public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
                    entered.countDown();
                    try {
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {}
                    setBoundPort(18091);
                    return true;
                }
            };

            final RemoteController controller = new RemoteController(new PairingClient(), slowBridge, store, new RemoteApiClient(), new File(tempDir, "tsnet"));

            Thread openThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        controller.open();
                    } catch (IOException ignored) {}
                }
            });
            openThread.start();

            if (!entered.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("open() never started bridge.begin");
            }

            // While open() holds controller lock and blocks in bridge.start, UI thread calls must return < 200ms
            final AtomicBoolean success = new AtomicBoolean(false);
            final long[] elapsed = new long[1];

            Thread uiThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    long start = System.currentTimeMillis();
                    controller.isPaired();
                    controller.getBoundLocalPort();
                    controller.getBridge();
                    elapsed[0] = System.currentTimeMillis() - start;
                    success.set(true);
                }
            });

            uiThread.start();
            uiThread.join(500);

            release.countDown();
            openThread.join(5000);

            if (!success.get()) {
                throw new AssertionError("UI thread calls blocked while open() was in flight!");
            }
            if (elapsed[0] >= 200) {
                throw new AssertionError("UI thread calls during open() took " + elapsed[0] + " ms (must be < 200 ms)");
            }

            controller.close();
            System.out.println("  PASS: testBehavioralOpenDoesNotBlockUiThread (" + elapsed[0] + " ms)");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] ch = f.listFiles();
            if (ch != null) {
                for (File c : ch) deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static class FakePcnetBridge extends PcnetBridge {
        int localPort = 18091;

        @Override
        public boolean isRunning() {
            return false;
        }
        @Override
        public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
            setBoundPort(this.localPort);
            return true;
        }

        @Override
        public vn.vuumi.pcdash.net.PcnetState state() {
            return new vn.vuumi.pcdash.net.PcnetState("running", "", java.util.Collections.singletonList("100.64.0.1"), localPort, "");
        }


        @Override
        public int start(File stateDir, String authKey, String controlUrl, int port) {
            return localPort;
        }

        @Override
        public int getBoundPort() {
            return localPort;
        }

        @Override
        public void stop() {}

        @Override
        public void checkRevoked() {}
    }
}
