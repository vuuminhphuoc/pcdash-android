package vn.vuumi.pcdash.remote;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import vn.vuumi.pcdash.net.PcnetBridge;

public class QuotaViewModelTest {

    private static class TestBridge extends PcnetBridge {
        int port;
        boolean running = false;

        @Override
        public boolean isNativeAvailable() {
            return true;
        }

        @Override
        public boolean begin(File stateDir, String hostname, int mode, String host, int hostPort, int localPort) {
            running = true;
            setBoundPort(port);
            return true;
        }

        @Override
        public vn.vuumi.pcdash.net.PcnetState state() {
            return new vn.vuumi.pcdash.net.PcnetState("running", "", java.util.Collections.singletonList("100.64.0.1"), port, "");
        }

        @Override
        public int start(File stateDir, String authKey, String host, int hostPort) {
            running = true;
            setBoundPort(port);
            return port;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void stop() {
            running = false;
            setBoundPort(-1);
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Running QuotaViewModelTest...");
        testEmptyProviders();
        testHasData();
        testAntigravityTwoGroupsTwoBarsEach();
        testCodexOnly30dLongWindow();
        testStrangeWindowIdBecomesLongWindow();
        testDetailsBlocksAndAccountBars();
        testRemoteApiClientGetQuota();
        testRemoteControllerGetQuotaUnsupportedRoute404();
        testRemoteControllerGetQuotaDeviceRevoked401();
        System.out.println("ALL QuotaViewModelTest assertions PASSED!");
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("Assertion failed: " + msg);
        }
    }

    public static void testEmptyProviders() {
        QuotaSnapshot empty1 = new QuotaSnapshot(1000L, false, false, null, Collections.<QuotaSnapshot.Provider>emptyList());
        List<QuotaViewModel.ProviderCard> cards1 = QuotaViewModel.build(empty1, 1000L);
        assertTrue(cards1 != null && cards1.isEmpty(), "empty snapshot gives empty cards");

        List<QuotaViewModel.ProviderCard> cardsNull = QuotaViewModel.build(null, 1000L);
        assertTrue(cardsNull != null && cardsNull.isEmpty(), "null snapshot gives empty cards");
        System.out.println("PASS: testEmptyProviders");
    }

    public static void testHasData() {
        assertTrue(!QuotaViewModel.hasData(null), "hasData(null) is false");
        assertTrue(!QuotaViewModel.hasData(Collections.<QuotaViewModel.ProviderCard>emptyList()), "hasData(empty) is false");

        List<QuotaViewModel.ProviderCard> emptyCardList = new ArrayList<QuotaViewModel.ProviderCard>();
        emptyCardList.add(new QuotaViewModel.ProviderCard("p1", "Provider 1",
                Collections.<QuotaViewModel.SummaryRow>emptyList(),
                Collections.<QuotaViewModel.DetailBlock>emptyList()));
        assertTrue(!QuotaViewModel.hasData(emptyCardList), "card with empty summary and details hasData is false");

        List<QuotaViewModel.SummaryRow> summary = new ArrayList<QuotaViewModel.SummaryRow>();
        summary.add(new QuotaViewModel.SummaryRow("g1", new QuotaViewModel.Bar(50, QuotaFormatter.Tier.HIGH, "hồi sau 1h"), null, null));
        List<QuotaViewModel.ProviderCard> validCardList = new ArrayList<QuotaViewModel.ProviderCard>();
        validCardList.add(new QuotaViewModel.ProviderCard("p1", "Provider 1", summary, Collections.<QuotaViewModel.DetailBlock>emptyList()));
        assertTrue(QuotaViewModel.hasData(validCardList), "card with summary hasData is true");
        System.out.println("PASS: testHasData");
    }

    public static void testAntigravityTwoGroupsTwoBarsEach() {
        long now = 1730000000000L;
        List<QuotaSnapshot.Account> accountsG1 = new ArrayList<QuotaSnapshot.Account>();
        accountsG1.add(new QuotaSnapshot.Account("user1@*", 70, now + 3600000L));

        QuotaSnapshot.Window w5hG1 = new QuotaSnapshot.Window("5h", "5 giờ", 85, 1, 1, now + 3600000L, accountsG1);
        QuotaSnapshot.Window wWeeklyG1 = new QuotaSnapshot.Window("weekly", "Tuần", 90, 1, 1, now + 86400000L, accountsG1);

        List<QuotaSnapshot.Window> windowsG1 = new ArrayList<QuotaSnapshot.Window>();
        windowsG1.add(w5hG1);
        windowsG1.add(wWeeklyG1);
        QuotaSnapshot.Group g1 = new QuotaSnapshot.Group("Gemini", windowsG1);

        QuotaSnapshot.Window w5hG2 = new QuotaSnapshot.Window("5h", "5 giờ", 40, 1, 1, now + 7200000L, accountsG1);
        QuotaSnapshot.Window wWeeklyG2 = new QuotaSnapshot.Window("weekly", "Tuần", 15, 1, 1, now + 172800000L, accountsG1);
        List<QuotaSnapshot.Window> windowsG2 = new ArrayList<QuotaSnapshot.Window>();
        windowsG2.add(w5hG2);
        windowsG2.add(wWeeklyG2);
        QuotaSnapshot.Group g2 = new QuotaSnapshot.Group("Claude & GPT (shared)", windowsG2);

        List<QuotaSnapshot.Group> groups = new ArrayList<QuotaSnapshot.Group>();
        groups.add(g1);
        groups.add(g2);

        QuotaSnapshot.Provider p = new QuotaSnapshot.Provider("google-antigravity", "Antigravity", groups);
        QuotaSnapshot snapshot = new QuotaSnapshot(now, false, false, null, Collections.singletonList(p));

        List<QuotaViewModel.ProviderCard> cards = QuotaViewModel.build(snapshot, now);
        assertTrue(cards.size() == 1, "1 provider card");
        QuotaViewModel.ProviderCard card = cards.get(0);
        assertTrue("Antigravity".equals(card.name), "card name is Antigravity");
        assertTrue(card.summary.size() == 2, "card summary has 2 rows");

        QuotaViewModel.SummaryRow row1 = card.summary.get(0);
        assertTrue("Gemini".equals(row1.group), "row 1 group Gemini");
        assertTrue(row1.fiveHour != null, "row 1 fiveHour not null");
        assertTrue(row1.fiveHour.pct == 85, "row 1 fiveHour pct 85");
        assertTrue(row1.fiveHour.tier == QuotaFormatter.Tier.HIGH, "row 1 fiveHour tier HIGH");
        assertTrue(row1.weekly != null, "row 1 weekly not null");
        assertTrue(row1.weekly.pct == 90, "row 1 weekly pct 90");
        assertTrue(row1.longWindow == null, "row 1 longWindow is null");

        QuotaViewModel.SummaryRow row2 = card.summary.get(1);
        assertTrue("Claude & GPT (shared)".equals(row2.group), "row 2 group Claude & GPT");
        assertTrue(row2.fiveHour != null, "row 2 fiveHour not null");
        assertTrue(row2.fiveHour.pct == 40, "row 2 fiveHour pct 40");
        assertTrue(row2.fiveHour.tier == QuotaFormatter.Tier.MID, "row 2 fiveHour tier MID");
        assertTrue(row2.weekly != null, "row 2 weekly not null");
        assertTrue(row2.weekly.pct == 15, "row 2 weekly pct 15");
        assertTrue(row2.weekly.tier == QuotaFormatter.Tier.LOW, "row 2 weekly tier LOW");
        assertTrue(row2.longWindow == null, "row 2 longWindow is null");

        System.out.println("PASS: testAntigravityTwoGroupsTwoBarsEach");
    }

    public static void testCodexOnly30dLongWindow() {
        long now = 1730000000000L;
        QuotaSnapshot.Window w30d = new QuotaSnapshot.Window("30d", "30 ngày", 60, 1, 1, null, Collections.<QuotaSnapshot.Account>emptyList());
        QuotaSnapshot.Group g = new QuotaSnapshot.Group("Codex", Collections.singletonList(w30d));
        QuotaSnapshot.Provider p = new QuotaSnapshot.Provider("openai-codex", "Codex", Collections.singletonList(g));
        QuotaSnapshot snapshot = new QuotaSnapshot(now, false, false, null, Collections.singletonList(p));

        List<QuotaViewModel.ProviderCard> cards = QuotaViewModel.build(snapshot, now);
        assertTrue(cards.size() == 1, "1 card");
        QuotaViewModel.ProviderCard card = cards.get(0);
        assertTrue(card.summary.size() == 1, "1 summary row");
        QuotaViewModel.SummaryRow row = card.summary.get(0);
        assertTrue("Codex".equals(row.group), "group Codex");
        assertTrue(row.fiveHour == null, "fiveHour null");
        assertTrue(row.weekly == null, "weekly null");
        assertTrue(row.longWindow != null, "longWindow present");
        assertTrue(row.longWindow.pct == 60, "longWindow pct 60");
        assertTrue(row.longWindow.tier == QuotaFormatter.Tier.HIGH, "longWindow tier HIGH");
        assertTrue("đang hồi".equals(row.longWindow.resetText), "longWindow resetText đang hồi");
        System.out.println("PASS: testCodexOnly30dLongWindow");
    }

    public static void testStrangeWindowIdBecomesLongWindow() {
        long now = 1730000000000L;
        QuotaSnapshot.Window wStrange = new QuotaSnapshot.Window("strange_rolling_window", "Kỳ lạ", 35, 1, 1, null, Collections.<QuotaSnapshot.Account>emptyList());
        QuotaSnapshot.Group g = new QuotaSnapshot.Group("Custom", Collections.singletonList(wStrange));
        QuotaSnapshot.Provider p = new QuotaSnapshot.Provider("custom-ai", "Custom AI", Collections.singletonList(g));
        QuotaSnapshot snapshot = new QuotaSnapshot(now, false, false, null, Collections.singletonList(p));

        List<QuotaViewModel.ProviderCard> cards = QuotaViewModel.build(snapshot, now);
        assertTrue(cards.size() == 1, "1 card");
        QuotaViewModel.ProviderCard card = cards.get(0);
        QuotaViewModel.SummaryRow row = card.summary.get(0);
        assertTrue(row.fiveHour == null, "fiveHour null");
        assertTrue(row.weekly == null, "weekly null");
        assertTrue(row.longWindow != null, "longWindow present for strange window id");
        assertTrue(row.longWindow.pct == 35, "longWindow pct 35");
        assertTrue(row.longWindow.tier == QuotaFormatter.Tier.MID, "longWindow tier MID");
        System.out.println("PASS: testStrangeWindowIdBecomesLongWindow");
    }

    public static void testDetailsBlocksAndAccountBars() {
        long now = 1730000000000L;
        List<QuotaSnapshot.Account> accounts = new ArrayList<QuotaSnapshot.Account>();
        accounts.add(new QuotaSnapshot.Account("user1@*", 80, now + 100000L));
        accounts.add(new QuotaSnapshot.Account("user2@*", 20, null));

        QuotaSnapshot.Window w = new QuotaSnapshot.Window("5h", "5 giờ", 50, 2, 2, now + 100000L, accounts);
        QuotaSnapshot.Group g = new QuotaSnapshot.Group("Gemini", Collections.singletonList(w));
        QuotaSnapshot.Provider p = new QuotaSnapshot.Provider("google-antigravity", "Antigravity", Collections.singletonList(g));
        QuotaSnapshot snapshot = new QuotaSnapshot(now, false, false, null, Collections.singletonList(p));

        List<QuotaViewModel.ProviderCard> cards = QuotaViewModel.build(snapshot, now);
        QuotaViewModel.ProviderCard card = cards.get(0);
        assertTrue(card.details.size() == 1, "1 detail block");
        QuotaViewModel.DetailBlock db = card.details.get(0);
        assertTrue("Gemini".equals(db.group), "detail block group");
        assertTrue(db.windows.size() == 1, "1 window detail");

        QuotaViewModel.WindowDetail wd = db.windows.get(0);
        assertTrue("5 giờ".equals(wd.label), "window detail label");
        assertTrue(wd.bar.pct == 50, "window detail bar pct 50");
        assertTrue(wd.accounts.size() == 2, "2 account bars");

        QuotaViewModel.AccountBar ab1 = wd.accounts.get(0);
        assertTrue("user1@*".equals(ab1.label), "ab1 label");
        assertTrue(ab1.bar.pct == 80, "ab1 pct 80");
        assertTrue(ab1.bar.tier == QuotaFormatter.Tier.HIGH, "ab1 tier HIGH");

        QuotaViewModel.AccountBar ab2 = wd.accounts.get(1);
        assertTrue("user2@*".equals(ab2.label), "ab2 label");
        assertTrue(ab2.bar.pct == 20, "ab2 pct 20");
        assertTrue(ab2.bar.tier == QuotaFormatter.Tier.MID, "ab2 tier MID");
        assertTrue("đang hồi".equals(ab2.bar.resetText), "ab2 resetText đang hồi");
        System.out.println("PASS: testDetailsBlocksAndAccountBars");
    }

    public static void testRemoteApiClientGetQuota() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();
        final String mockJson = "{\"fetchedAt\":1730000000000,\"providers\":[]}";
        final boolean[] sawRefreshQuery = new boolean[1];

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // First request: without refresh
                    Socket s1 = server.accept();
                    byte[] buf1 = new byte[1024];
                    int len1 = s1.getInputStream().read(buf1);
                    String req1 = new String(buf1, 0, len1, StandardCharsets.UTF_8);
                    boolean isGet1 = req1.startsWith("GET /v1/quota HTTP/1.1");
                    String resp1 = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + mockJson.length() + "\r\n\r\n" + mockJson;
                    s1.getOutputStream().write(resp1.getBytes(StandardCharsets.UTF_8));
                    s1.close();

                    // Second request: with refresh
                    Socket s2 = server.accept();
                    byte[] buf2 = new byte[1024];
                    int len2 = s2.getInputStream().read(buf2);
                    String req2 = new String(buf2, 0, len2, StandardCharsets.UTF_8);
                    sawRefreshQuery[0] = req2.startsWith("GET /v1/quota?refresh=1 HTTP/1.1");
                    String resp2 = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + mockJson.length() + "\r\n\r\n" + mockJson;
                    s2.getOutputStream().write(resp2.getBytes(StandardCharsets.UTF_8));
                    s2.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        RemoteApiClient client = new RemoteApiClient();
        String res1 = client.getQuota(port, "token123", false);
        assertTrue(mockJson.equals(res1), "res1 matches mockJson");

        String res2 = client.getQuota(port, "token123", true);
        assertTrue(mockJson.equals(res2), "res2 matches mockJson");
        assertTrue(sawRefreshQuery[0], "res2 requested ?refresh=1");

        server.close();
        System.out.println("PASS: testRemoteApiClientGetQuota");
    }

    public static void testRemoteControllerGetQuotaUnsupportedRoute404() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    byte[] buf = new byte[1024];
                    s.getInputStream().read(buf);
                    String resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n";
                    s.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "quota_404_test_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");
        File stateDir = new File(tempDir, "tsnet");
        RemoteConfigStore store = new RemoteConfigStore(configFile);
        store.save(new RemoteConfigStore.RemoteConfig("tok_404", "100.64.0.1", 8091));

        TestBridge bridge = new TestBridge();
        bridge.port = port;

        RemoteApiClient client = new RemoteApiClient();
        RemoteController controller = new RemoteController(new PairingClient(), bridge, store, client, stateDir);
        controller.open();

        try {
            controller.getQuota(false);
            throw new AssertionError("Expected IOException on 404");
        } catch (IOException e) {
            assertTrue(e.getMessage() != null && e.getMessage().contains("chưa hỗ trợ"),
                    "404 error message must contain 'chưa hỗ trợ': " + e.getMessage());
        } finally {
            controller.close();
            server.close();
            tempDir.delete();
        }
        System.out.println("PASS: testRemoteControllerGetQuotaUnsupportedRoute404");
    }

    public static void testRemoteControllerGetQuotaDeviceRevoked401() throws Exception {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        final int port = server.getLocalPort();

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = server.accept();
                    byte[] buf = new byte[1024];
                    s.getInputStream().read(buf);
                    String resp = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n";
                    s.getOutputStream().write(resp.getBytes(StandardCharsets.UTF_8));
                    s.close();
                } catch (Exception ignored) {}
            }
        });
        t.setDaemon(true);
        t.start();

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "quota_401_test_" + System.currentTimeMillis());
        tempDir.mkdirs();
        File configFile = new File(tempDir, "config.json");
        File stateDir = new File(tempDir, "tsnet");
        RemoteConfigStore store = new RemoteConfigStore(configFile);
        store.save(new RemoteConfigStore.RemoteConfig("tok_401", "100.64.0.1", 8091));

        TestBridge bridge = new TestBridge();
        bridge.port = port;

        RemoteApiClient client = new RemoteApiClient();
        RemoteController controller = new RemoteController(new PairingClient(), bridge, store, client, stateDir);
        controller.open();

        try {
            controller.getQuota(false);
            throw new AssertionError("Expected DeviceRevokedException on 401");
        } catch (RemoteApiClient.DeviceRevokedException e) {
            // Expected
        } finally {
            controller.close();
            server.close();
            tempDir.delete();
        }
        System.out.println("PASS: testRemoteControllerGetQuotaDeviceRevoked401");
    }
}
