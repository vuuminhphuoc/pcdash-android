package vn.vuumi.pcdash.qr;

import android.graphics.Bitmap;
import android.graphics.Color;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.EnumMap;
import java.util.Map;

/** Draws a QR code as a crisp black-on-white bitmap (whole pixels per module, with a quiet zone). */
public final class QrBitmap {
    private QrBitmap() {}

    /** @return the code at the largest whole scale that fits in {@code maxSizePx}, or null if it cannot be encoded */
    public static Bitmap encode(String text, int maxSizePx) {
        Map<EncodeHintType, Object> hints = new EnumMap<EncodeHintType, Object>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 2);
        BitMatrix m;
        try {
            // Size 0: one cell per module; scaled below so every module has the same pixel width.
            m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints);
        } catch (WriterException | IllegalArgumentException e) {
            return null;
        }
        int modules = m.getWidth();
        int scale = Math.max(1, maxSizePx / modules);
        int size = modules * scale;
        int[] pixels = new int[size * size];
        for (int y = 0; y < size; y++) {
            int my = y / scale;
            for (int x = 0; x < size; x++) {
                pixels[y * size + x] = m.get(x / scale, my) ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, size, 0, 0, size, size);
        return bmp;
    }
}
