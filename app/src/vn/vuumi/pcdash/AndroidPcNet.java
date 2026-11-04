package vn.vuumi.pcdash;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The real network for {@link PcLocator}: TCP connects, the agent's proof route and this phone's addresses. */
final class AndroidPcNet implements PcLocator.Net {
    private static final Pattern PROOF = Pattern.compile("\"proof\"\\s*:\\s*\"([0-9a-fA-F]{64})\"");

    @Override
    public boolean isOpen(String ip, int port, int timeoutMs) {
        return PcConn.tcpOpen(ip, port, timeoutMs);
    }

    /** Asks the agent for its proof. Nothing secret is sent: no token and no signature. */
    @Override
    public String whoami(String ip, int port, String challenge, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://" + ip + ":" + port + "/whoami?c=" + challenge).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            if (code != 200) {
                // Something answered HTTP but is not an agent that knows the proof route.
                return code >= 400 ? PcLocator.UNSUPPORTED : null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 1024) out.write(buf, 0, n);
            in.close();
            Matcher m = PROOF.matcher(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : PcLocator.UNSUPPORTED;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The phone's private IPv4 address; the one on the same /16 as the PC's old address when there are several. */
    @Override
    public PcLocator.Subnet localSubnet(String nearIp) {
        PcLocator.Subnet fallback = null;
        long near = PcLocator.toLong(nearIp);
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                for (InterfaceAddress ia : nif.getInterfaceAddresses()) {
                    if (!(ia.getAddress() instanceof Inet4Address)) continue;
                    String ip = ia.getAddress().getHostAddress();
                    if (!PcSettings.isValidRfc1918Ip(ip)) continue;
                    PcLocator.Subnet s = new PcLocator.Subnet(ip, ia.getNetworkPrefixLength());
                    if (near >= 0 && (PcLocator.toLong(ip) >> 16) == (near >> 16)) return s;
                    if (fallback == null) fallback = s;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }
}
