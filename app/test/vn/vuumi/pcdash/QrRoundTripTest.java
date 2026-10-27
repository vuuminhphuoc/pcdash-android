package vn.vuumi.pcdash;

import com.google.zxing.RGBLuminanceSource;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/** Decodes the PNGs written by agent/testdata/QrDump.cs with the same QrDecoder the host app uses. */
public class QrRoundTripTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[0]);
        File[] pngs = dir.listFiles((d, name) -> name.endsWith(".png"));
        if (pngs == null || pngs.length == 0) throw new AssertionError("FAILED: no PNGs in " + dir);
        QrDecoder decoder = new QrDecoder();
        int checked = 0;
        for (File png : pngs) {
            String expected = new String(Files.readAllBytes(new File(dir, png.getName().replace(".png", ".txt")).toPath()), StandardCharsets.UTF_8);
            BufferedImage img = ImageIO.read(png);
            int w = img.getWidth();
            int h = img.getHeight();
            int[] pixels = img.getRGB(0, 0, w, h, null, 0, w);
            String actual = decoder.decode(new RGBLuminanceSource(w, h, pixels));
            if (!expected.equals(actual)) {
                throw new AssertionError("FAILED: " + png.getName() + " expected [" + expected + "] got [" + actual + "]");
            }
            checked++;
        }
        PcProvisioning p = PcProvisioning.parse(new String(Files.readAllBytes(new File(dir, "payload.txt").toPath()), StandardCharsets.UTF_8));
        if (p == null || !"192.168.1.50".equals(p.ip) || p.port != 8799) throw new AssertionError("FAILED: payload.png did not parse");
        System.out.println("QrRoundTripTest OK (" + checked + " QR codes decoded)");
    }
}
