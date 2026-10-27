package vn.vuumi.pcdash;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class CommandLogTest {

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) throw new AssertionError("FAILED: " + msg);
    }

    private static void assertEquals(Object actual, Object expected, String msg) {
        if (actual == null && expected == null) return;
        if (actual == null || !actual.equals(expected)) {
            throw new AssertionError("FAILED: " + msg + " (expected: " + expected + ", got: " + actual + ")");
        }
    }

    public static void testCap500() throws IOException {
        File f = File.createTempFile("cmdlog-cap-", ".jsonl");
        f.deleteOnExit();
        try {
            CommandLog log = new CommandLog(f);
            for (int i = 1; i <= 550; i++) {
                log.log(1000L + i, "phone-" + i, "remote", "wake", true);
            }
            List<CommandLog.LogEntry> entries = log.getEntries(1000);
            assertEquals(entries.size(), 500, "Should cap at 500 entries");
            // Newest first
            assertEquals(entries.get(0).ts, 1550L, "Newest entry should be index 550");
            assertEquals(entries.get(0).device, "phone-550", "Newest device should match");
            assertEquals(entries.get(499).ts, 1051L, "Oldest kept entry should be index 51");
        } finally {
            f.delete();
        }
    }

    public static void testConcurrency() throws Exception {
        File f = File.createTempFile("cmdlog-conc-", ".jsonl");
        f.deleteOnExit();
        try {
            final CommandLog log = new CommandLog(f);
            int threadCount = 10;
            final int writesPerThread = 50;
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            final CountDownLatch latch = new CountDownLatch(threadCount);
            final AtomicInteger errors = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int i = 0; i < writesPerThread; i++) {
                                log.log(System.currentTimeMillis(), "dev-" + threadId, "remote", "sleep", i % 2 == 0);
                            }
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    }
                });
            }

            // Guards against a deadlock, not disk speed: each write rewrites and fsyncs the file, and
            // 500 of them took 9-11 s on the Windows CI runner.
            boolean done = latch.await(60, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(done, "Concurrent writes must complete in time");
            assertEquals(errors.get(), 0, "No exceptions during concurrent writes");
            assertEquals(log.getEntries(1000).size(), 500, "Must contain exactly 500 entries");
        } finally {
            f.delete();
        }
    }

    public static void testReloadAfterRestart() throws IOException {
        File f = File.createTempFile("cmdlog-reload-", ".jsonl");
        f.deleteOnExit();
        try {
            CommandLog log = new CommandLog(f);
            for (int i = 1; i <= 100; i++) {
                log.log(2000L + i, "Pixel " + i, i % 2 == 0 ? "web" : "remote", "shutdown", i % 3 != 0);
            }

            // Simulate restart by creating a new CommandLog instance pointing to same file
            CommandLog reloaded = new CommandLog(f);
            List<CommandLog.LogEntry> entries = reloaded.getEntries(200);
            assertEquals(entries.size(), 100, "Reloaded log should have 100 entries");
            assertEquals(entries.get(0).ts, 2100L, "Newest entry should be index 100");
            assertEquals(entries.get(0).device, "Pixel 100", "Newest device should match");
            assertEquals(entries.get(0).source, "web", "Newest source should match");
            assertEquals(entries.get(0).action, "shutdown", "Newest action should match");
            assertEquals(entries.get(0).ok, true, "Newest ok should match");

            assertEquals(entries.get(99).ts, 2001L, "Oldest entry should be index 1");
            assertEquals(entries.get(99).device, "Pixel 1", "Oldest device should match");
            assertEquals(entries.get(99).source, "remote", "Oldest source should match");
            assertEquals(entries.get(99).action, "shutdown", "Oldest action should match");
            assertEquals(entries.get(99).ok, true, "Oldest ok should match");
        } finally {
            f.delete();
        }
    }

    public static void testCorruptFile() throws IOException {
        File f = File.createTempFile("cmdlog-corrupt-", ".jsonl");
        f.deleteOnExit();
        try {
            // Write some corrupt lines mixed with valid lines
            try (FileOutputStream fos = new FileOutputStream(f)) {
                String content = "{\"ts\":1000,\"device\":\"valid1\",\"source\":\"remote\",\"action\":\"wake\",\"ok\":true}\n"
                        + "not json at all\n"
                        + "{\"ts\":\"bad_ts\",\"device\":\"invalid\"}\n"
                        + "\n"
                        + "{\"broken json\n"
                        + "\u0000\u0001\u0002random bytes\n"
                        + "{\"ts\":2000,\"device\":\"valid2\",\"source\":\"web\",\"action\":\"abort\",\"ok\":false}\n";
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }

            CommandLog log = new CommandLog(f);
            List<CommandLog.LogEntry> entries = log.getEntries(50);
            assertEquals(entries.size(), 2, "Should recover exactly 2 valid entries");
            assertEquals(entries.get(0).device, "valid2", "Newest valid entry matches");
            assertEquals(entries.get(0).ok, false, "valid2 ok is false");
            assertEquals(entries.get(1).device, "valid1", "Older valid entry matches");
            assertEquals(entries.get(1).ok, true, "valid1 ok is true");
        } finally {
            f.delete();
        }
    }

    public static void testClear() throws IOException {
        File f = File.createTempFile("cmdlog-clear-", ".jsonl");
        f.deleteOnExit();
        try {
            CommandLog log = new CommandLog(f);
            log.log(1000L, "d1", "remote", "wake", true);
            log.log(2000L, "d2", "web", "sleep", false);
            assertEquals(log.getEntries(50).size(), 2, "Should have 2 entries");

            log.clear();
            assertEquals(log.getEntries(50).size(), 0, "Should be empty after clear");

            CommandLog reloaded = new CommandLog(f);
            assertEquals(reloaded.getEntries(50).size(), 0, "Reloaded after clear should be empty");
        } finally {
            f.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running CommandLogTest...");
        testCap500();
        System.out.println("  PASS: testCap500");
        testConcurrency();
        System.out.println("  PASS: testConcurrency");
        testReloadAfterRestart();
        System.out.println("  PASS: testReloadAfterRestart");
        testCorruptFile();
        System.out.println("  PASS: testCorruptFile");
        testClear();
        System.out.println("  PASS: testClear");
        System.out.println("ALL CommandLogTest PASSED");
    }
}
