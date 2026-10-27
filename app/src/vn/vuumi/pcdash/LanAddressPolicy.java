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

    public static boolean isTailnetIpv4(InetAddress address) {
        if (address == null || !(address instanceof Inet4Address)) return false;
        byte[] bytes = address.getAddress();
        int b0 = bytes[0] & 0xFF;
        int b1 = bytes[1] & 0xFF;
        return b0 == 100 && b1 >= 64 && b1 <= 127;
    }
}
