package vn.vuumi.pcdash;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.EnumMap;
import java.util.Map;

/** Decodes QR codes from a camera luminance (Y) plane using ZXing. Not thread-safe; use one per thread. */
public final class QrDecoder {
    private final QRCodeReader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

    public QrDecoder() {
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
    }

    /** @param luma tightly packed width*height bytes of the Y plane */
    public String decodeLuma(byte[] luma, int width, int height) {
        return decode(new PlanarYUVLuminanceSource(luma, width, height, 0, 0, width, height, false));
    }

    public String decode(LuminanceSource source) {
        try {
            Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(source)), hints);
            return r.getText();
        } catch (ReaderException e) {
            return null;
        } finally {
            reader.reset();
        }
    }
}
