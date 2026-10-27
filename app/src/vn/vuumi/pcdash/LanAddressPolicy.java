package vn.vuumi.pcdash;

import java.net.Inet4Address;
import java.net.InetAddress;

public class LanAddressPolicy {

    public static boolean isAllowedPairingPeer(InetAddress address) {
        if (address == null) return false;
        if (!(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;

        // Reject loopback (127.0.0.0/8)
        if (b0 == 127) return false;

        // Reject Tailscale CGNAT (100.64.0.0/10)
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return false;

        // RFC 1918: 10.0.0.0/8
        if (b0 == 10) return true;

        // RFC 1918: 172.16.0.0/12
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;

        // RFC 1918: 192.168.0.0/16
        if (b0 == 192 && b1 == 168) return true;

        // Reject public IPv4, link-local (169.254.x.x), multicast (224.x.x.x)
        return false;
    }

    /**
     * Who may talk to the :8090 web dashboard: this phone, the home LAN (RFC 1918, link-local, IPv6 ULA)
     * and a Tailscale app on the phone (100.64.0.0/10). Public addresses are refused, so the dashboard
     * stays closed when mobile data hands the phone a routable IPv4 or IPv6 address.
     */
    public static boolean isAllowedDashboardPeer(InetAddress address) {
        if (address == null) return false;
        byte[] b = address.getAddress();
        if (b.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) { mapped = false; break; }
            }
            if (mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
                b = new byte[] { b[12], b[13], b[14], b[15] };
            } else {
                if (address.isLoopbackAddress() || address.isLinkLocalAddress()) return true;
                return ((b[0] & 0xFE) == 0xFC); // fc00::/7 unique local, includes Tailscale fd7a:115c:a1e0::/48
            }
        }
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        if (b0 == 127) return true;
        if (b0 == 10) return true;
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
        if (b0 == 192 && b1 == 168) return true;
        if (b0 == 169 && b1 == 254) return true;
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }

    public static boolean isTailnetIpv4(InetAddress address) {
        if (address == null || !(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }
}
