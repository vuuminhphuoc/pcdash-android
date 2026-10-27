package vn.vuumi.pcdash;

import vn.vuumi.pcdash.i18n.L10n;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Scans the setup QR code that PcAgent shows on the PC. Returns the raw text in {@link #EXTRA_TEXT}
 * once a valid PCDash payload is seen. Camera2 + ZXing, no extra scanner app needed.
 */
public class QrScanActivity extends Activity implements TextureView.SurfaceTextureListener {
    public static final String EXTRA_TEXT = "text";

    private static final int REQ_CAMERA = 71;
    private static final long DECODE_INTERVAL_MS = 120;
    private static final long NOT_PCDASH_HINT_MS = 2500;
    private static String hintDefault() {
        return L10n.t("Point the camera at the QR code on the PC screen", "Hướng camera vào mã QR đang hiện trên màn hình PC");
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Object cameraLock = new Object();

    private FrameLayout root;
    private TextureView textureView;
    private TextView hintView;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private Size previewSize;
    private boolean swapAxes;
    private boolean opening;
    private boolean askedPermission;
    private volatile boolean done;
    private long lastDecodeAt;
    private long lastNotPcdashAt;
    private QrDecoder decoder;
    private byte[] lumaBuf;

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        if (getActionBar() != null) {
            getActionBar().hide();
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        textureView = new TextureView(this);
        textureView.setSurfaceTextureListener(this);
        root.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        View frame = new View(this);
        GradientDrawable frameBg = new GradientDrawable();
        frameBg.setColor(Color.TRANSPARENT);
        frameBg.setStroke(dp(3), Color.argb(230, 88, 166, 255));
        frameBg.setCornerRadius(dp(20));
        frame.setBackground(frameBg);
        frame.setContentDescription(L10n.t("QR code scan frame", "Khung quét mã QR"));
        root.addView(frame, new FrameLayout.LayoutParams(dp(260), dp(260), Gravity.CENTER));

        hintView = new TextView(this);
        hintView.setText(hintDefault());
        hintView.setTextColor(Color.WHITE);
        hintView.setTextSize(15);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(dp(24), dp(14), dp(24), dp(14));
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(Color.argb(170, 0, 0, 0));
        hintBg.setCornerRadius(dp(16));
        hintView.setBackground(hintBg);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hintLp.topMargin = dp(48);
        root.addView(hintView, hintLp);

        Button cancel = new Button(this);
        cancel.setText(L10n.t("Cancel", "Hủy"));
        cancel.setTextColor(Color.WHITE);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(Color.argb(200, 33, 38, 45));
        cancelBg.setStroke(dp(1), Color.rgb(48, 54, 61));
        cancelBg.setCornerRadius(dp(24));
        cancel.setBackground(cancelBg);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(
                dp(160), dp(48), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelLp.bottomMargin = dp(40);
        root.addView(cancel, cancelLp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        done = false;
        decoder = new QrDecoder();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
            }
            return;
        }
        startCameraThread();
        if (textureView.isAvailable()) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCameraThread();
            if (textureView.isAvailable()) {
                openCamera();
            }
        } else {
            Toast.makeText(this, L10n.t("Camera permission is needed to scan the QR code. You can type the values in PC settings instead.", "Cần quyền camera để quét mã QR. Có thể nhập tay trong Cài đặt PC."), Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) return;
        cameraThread = new HandlerThread("pcdash-qr-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void openCamera() {
        synchronized (cameraLock) {
            if (opening || camera != null || cameraHandler == null || done) return;
            opening = true;
        }
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = pickBackCamera(cm);
            if (id == null) {
                fail(L10n.t("This phone has no rear camera", "Máy này không có camera sau"));
                return;
            }
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            StreamConfigurationMap map = cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail(L10n.t("Cannot read the camera configuration", "Không đọc được cấu hình camera"));
                return;
            }
            previewSize = chooseSize(map.getOutputSizes(SurfaceTexture.class), map.getOutputSizes(ImageFormat.YUV_420_888));
            if (previewSize == null) {
                fail(L10n.t("The camera has no suitable resolution", "Camera không hỗ trợ độ phân giải phù hợp"));
                return;
            }
            Integer orientation = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
            swapAxes = orientation != null && orientation % 180 == 90;
            fitPreview();

            reader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    handleImage(r);
                }
            }, cameraHandler);
            cm.openCamera(id, stateCallback, cameraHandler);
        } catch (SecurityException e) {
            fail(L10n.t("Camera permission not granted", "Chưa được cấp quyền camera"));
        } catch (CameraAccessException e) {
            fail(L10n.t("Cannot open the camera", "Không mở được camera"));
        } catch (RuntimeException e) {
            fail(L10n.t("Cannot open the camera", "Không mở được camera"));
        }
    }

    private static String pickBackCamera(CameraManager cm) throws CameraAccessException {
        String first = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (first == null) first = id;
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        return first;
    }

    /** Largest size (up to 1280x960) that both the preview texture and the analysis reader support. */
    private static Size chooseSize(Size[] textureSizes, Size[] yuvSizes) {
        if (textureSizes == null || yuvSizes == null) return null;
        Size best = null;
        for (Size t : textureSizes) {
            if (t.getWidth() * t.getHeight() > 1280 * 960) continue;
            boolean inYuv = false;
            for (Size y : yuvSizes) {
                if (y.equals(t)) {
                    inYuv = true;
                    break;
                }
            }
            if (!inYuv) continue;
            if (best == null || t.getWidth() * t.getHeight() > best.getWidth() * best.getHeight()) {
                best = t;
            }
        }
        return best;
    }

    private void fitPreview() {
        final Size size = previewSize;
        final boolean swap = swapAxes;
        ui.post(new Runnable() {
            @Override
            public void run() {
                int rw = root.getWidth();
                int rh = root.getHeight();
                if (size == null || rw == 0 || rh == 0) return;
                float aspect = swap ? (float) size.getHeight() / size.getWidth() : (float) size.getWidth() / size.getHeight();
                int vw;
                int vh;
                if ((float) rw / rh > aspect) {
                    vh = rh;
                    vw = Math.round(rh * aspect);
                } else {
                    vw = rw;
                    vh = Math.round(rw / aspect);
                }
                textureView.setLayoutParams(new FrameLayout.LayoutParams(vw, vh, Gravity.CENTER));
            }
        });
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice device) {
            boolean close;
            synchronized (cameraLock) {
                opening = false;
                close = done || cameraThread == null;
                if (!close) camera = device;
            }
            if (close) {
                device.close();
                return;
            }
            startPreview(device);
        }

        @Override
        public void onDisconnected(CameraDevice device) {
            device.close();
            synchronized (cameraLock) {
                opening = false;
                if (camera == device) camera = null;
            }
        }

        @Override
        public void onError(CameraDevice device, int error) {
            device.close();
            synchronized (cameraLock) {
                opening = false;
                if (camera == device) camera = null;
            }
            fail(L10n.t("Camera error (", "Camera gặp lỗi (") + error + ")");
        }
    };

    private void startPreview(final CameraDevice device) {
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            ImageReader r = reader;
            if (st == null || r == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(st);
            final CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            b.addTarget(r.getSurface());
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(previewSurface, r.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            synchronized (cameraLock) {
                                if (camera != device || done) {
                                    s.close();
                                    return;
                                }
                                session = s;
                            }
                            try {
                                s.setRepeatingRequest(b.build(), null, cameraHandler);
                            } catch (CameraAccessException e) {
                                fail(L10n.t("Cannot start the camera preview", "Không bật được xem trước camera"));
                            } catch (IllegalStateException e) {
                                // session closed while pausing
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            fail(L10n.t("Cannot start the camera preview", "Không bật được xem trước camera"));
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            fail(L10n.t("Cannot start the camera preview", "Không bật được xem trước camera"));
        } catch (RuntimeException e) {
            fail(L10n.t("Cannot start the camera preview", "Không bật được xem trước camera"));
        }
    }

    private void handleImage(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null || done) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastDecodeAt < DECODE_INTERVAL_MS) return;
            lastDecodeAt = now;

            Image.Plane plane = img.getPlanes()[0];
            ByteBuffer buf = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int w = img.getWidth();
            int h = img.getHeight();
            if (lumaBuf == null || lumaBuf.length != w * h) {
                lumaBuf = new byte[w * h];
            }
            if (rowStride == w) {
                buf.get(lumaBuf, 0, w * h);
            } else {
                for (int row = 0; row < h; row++) {
                    buf.position(row * rowStride);
                    buf.get(lumaBuf, row * w, w);
                }
            }
            QrDecoder d = decoder;
            String text = d != null ? d.decodeLuma(lumaBuf, w, h) : null;
            if (text == null) return;
            if (PcProvisioning.parse(text) != null) {
                deliver(text);
            } else if (now - lastNotPcdashAt > NOT_PCDASH_HINT_MS) {
                lastNotPcdashAt = now;
                showHint(L10n.t("This is not a PCDash setup QR code", "Mã QR này không phải mã cài đặt của PCDash"));
            }
        } catch (IllegalStateException e) {
            // reader closed while pausing
        } catch (RuntimeException e) {
            // skip a bad frame
        } finally {
            if (img != null) img.close();
        }
    }

    private void deliver(final String text) {
        if (done) return;
        done = true;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text));
                finish();
            }
        });
    }

    private void showHint(final String text) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                hintView.setText(text);
            }
        });
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                hintView.setText(hintDefault());
            }
        }, NOT_PCDASH_HINT_MS);
    }

    private void fail(final String message) {
        synchronized (cameraLock) {
            opening = false;
        }
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) return;
                Toast.makeText(QrScanActivity.this, message + L10n.t(". You can type the values in PC settings instead.", ". Có thể nhập tay trong Cài đặt PC."), Toast.LENGTH_LONG).show();
                setResult(RESULT_CANCELED);
                finish();
            }
        });
    }

    private void closeCamera() {
        CameraCaptureSession s;
        CameraDevice c;
        ImageReader r;
        HandlerThread t;
        synchronized (cameraLock) {
            done = true;
            s = session;
            c = camera;
            r = reader;
            t = cameraThread;
            session = null;
            camera = null;
            reader = null;
            cameraThread = null;
            cameraHandler = null;
            opening = false;
        }
        try {
            if (s != null) s.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (c != null) c.close();
        } catch (RuntimeException ignored) {
        }
        try {
            if (r != null) r.close();
        } catch (RuntimeException ignored) {
        }
        if (t != null) t.quitSafely();
        decoder = null;
        lumaBuf = null;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && cameraThread != null) {
            openCamera();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }
}
