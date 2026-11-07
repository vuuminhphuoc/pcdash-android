package vn.vuumi.pcdash;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import vn.vuumi.pcdash.net.PcnetState;

/** Runs the real PairingManager on sockets so the Go client can pair with it. */
public class PairHostE2E {
    static class FakeTailnet extends HostTailnet {
        @Override public String tailnetIp() { return "100.64.7.7"; }
        @Override public boolean isLoggedIn() { return true; }
        @Override public PcnetState state() {
            return new PcnetState("running", "", Collections.singletonList("100.64.7.7"), 8091, "");
        }
    }

    public static void main(String[] a) throws Exception {
        File f = File.createTempFile("e2e_store_", ".json");
        f.deleteOnExit();
        RemoteDeviceStore store = new RemoteDeviceStore(f);
        PairingManager m = new PairingManager(store, new FakeTailnet(), TimeSource.SYSTEM);
        PairingManager.PairingCode c = m.begin();
        m.start(52291, 52292);
        System.out.println("CODE=" + c.getCode() + " PORT=52292");
        System.out.flush();
        File tokenFile = new File(a[0]);
        long end = System.currentTimeMillis() + 120000;
        while (System.currentTimeMillis() < end && (!tokenFile.exists() || tokenFile.length() == 0)) Thread.sleep(200);
        if (!tokenFile.exists() || tokenFile.length() == 0) {
            System.out.println("NO_TOKEN_FILE");
            System.exit(2);
        }
        String token = new String(Files.readAllBytes(tokenFile.toPath()), StandardCharsets.UTF_8).trim();
        RemoteDeviceStore.RemoteDevice d = store.identify(token);
        System.out.println(d == null ? "NOT_IDENTIFIED" : "IDENTIFIED name=" + d.name + " node=" + d.nodeId);
        System.out.flush();
        m.close();
        System.exit(d == null ? 1 : 0);
    }
}
