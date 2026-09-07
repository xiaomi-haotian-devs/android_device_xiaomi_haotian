package com.mipay.wallet.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.InvertedLuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.mipay.wallet.R;
import com.mipay.wallet.qr.CloudRuleStore;
import com.mipay.wallet.qr.QrRouting;

import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal local camera scanner. Frames and decoded payloads never leave this process. */
public final class QrScannerActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 4201;
    private static final long DECODE_INTERVAL_MS = 220L;
    private static final ExecutorService DECODE_EXECUTOR =
            Executors.newSingleThreadExecutor();

    private TextureView previewView;
    private TextView cloudStatus;
    private Button cloudUpdateButton;
    private Button cloudDeleteButton;
    private CloudRuleStore cloudRuleStore;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest repeatingRequest;
    private ImageReader imageReader;
    private Surface previewSurface;
    private Size previewSize;
    private int sensorOrientation;
    private int cameraGeneration;
    private volatile boolean cameraActive;
    private volatile boolean cameraOpening;
    private volatile boolean decodeInFlight;
    private volatile boolean resultOpen;
    private volatile boolean cloudUpdating;
    private long lastDecodeAt;

    private final TextureView.SurfaceTextureListener surfaceListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width,
                        int height) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width,
                        int height) {
                    configurePreviewTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                }
            };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setDecorFitsSystemWindows(false);
        cloudRuleStore = new CloudRuleStore(this);
        setContentView(buildContent());
        configureSystemBars();
        refreshCloudStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cameraActive = true;
        resultOpen = cloudUpdating;
        startCameraThread();
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST);
        } else if (previewView.isAvailable()) {
            openCamera();
        } else {
            previewView.setSurfaceTextureListener(surfaceListener);
        }
    }

    @Override
    protected void onPause() {
        cameraActive = false;
        closeCamera();
        stopCameraThread();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (previewView.isAvailable()) {
                openCamera();
            }
        } else {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.qr_camera_permission_title)
                    .setMessage(R.string.qr_camera_permission_desc)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> finish())
                    .setCancelable(false)
                    .show();
        }
    }

    private View buildContent() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new TextureView(this);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(new ScannerOverlay(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(8), dp(18), dp(8));
        top.setBackgroundColor(0x66000000);
        top.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(14) + bars.left, dp(8) + bars.top,
                    dp(18) + bars.right, dp(8));
            return insets;
        });

        Button back = new Button(this);
        back.setText(R.string.back);
        back.setTextColor(Color.WHITE);
        back.setTextSize(15);
        back.setAllCaps(false);
        back.setBackground(ripple(0x00000000, 14));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(72), dp(48)));

        TextView title = new TextView(this);
        title.setText(R.string.qr_scanner_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(21);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));

        View spacer = new View(this);
        top.addView(spacer, new LinearLayout.LayoutParams(dp(72), dp(48)));

        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        root.addView(top, topParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(18), dp(16), dp(18), dp(14));
        controls.setBackground(rounded(0xee17191c, 22));
        controls.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.navigationBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(18) + bars.left, dp(16),
                    dp(18) + bars.right, dp(14) + bars.bottom);
            return insets;
        });

        TextView hint = new TextView(this);
        hint.setText(R.string.qr_scanner_hint);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        controls.addView(hint, matchWrap());

        cloudStatus = new TextView(this);
        cloudStatus.setTextColor(0xffc7cbd1);
        cloudStatus.setTextSize(13);
        cloudStatus.setGravity(Gravity.CENTER);
        cloudStatus.setPadding(0, dp(8), 0, dp(12));
        controls.addView(cloudStatus, matchWrap());

        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.CENTER);
        cloudUpdateButton = actionButton(R.string.qr_cloud_download);
        cloudUpdateButton.setOnClickListener(v -> confirmCloudUpdate());
        buttons.addView(cloudUpdateButton,
                new LinearLayout.LayoutParams(0, dp(46), 1f));

        cloudDeleteButton = actionButton(R.string.qr_cloud_delete);
        cloudDeleteButton.setOnClickListener(v -> confirmCloudDelete());
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(46), 1f);
        deleteParams.setMargins(dp(10), 0, 0, 0);
        buttons.addView(cloudDeleteButton, deleteParams);
        controls.addView(buttons, matchWrap());

        FrameLayout.LayoutParams controlsParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        controlsParams.setMargins(dp(12), 0, dp(12), dp(12));
        root.addView(controls, controlsParams);
        root.requestApplyInsets();
        return root;
    }

    private void confirmCloudUpdate() {
        resultOpen = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.qr_cloud_confirm_title)
                .setMessage(R.string.qr_cloud_confirm_desc)
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> resultOpen = false)
                .setPositiveButton(R.string.qr_cloud_download, (dialog, which) -> updateCloud())
                .setOnCancelListener(dialog -> resultOpen = false)
                .show();
    }

    private void updateCloud() {
        cloudUpdating = true;
        resultOpen = true;
        cloudUpdateButton.setEnabled(false);
        cloudUpdateButton.setText(R.string.qr_cloud_downloading);
        cloudRuleStore.update(result -> runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            cloudUpdating = false;
            resultOpen = false;
            cloudUpdateButton.setEnabled(true);
            refreshCloudStatus();
            if (result.success) {
                Toast.makeText(this,
                        getString(R.string.qr_cloud_updated_toast,
                                result.stats.acceptedCount, result.stats.rejectedCount),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this,
                        getString(R.string.qr_cloud_update_failed, result.error),
                        Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void confirmCloudDelete() {
        resultOpen = true;
        new AlertDialog.Builder(this)
                .setTitle(R.string.qr_cloud_delete_title)
                .setMessage(R.string.qr_cloud_delete_desc)
                .setNegativeButton(R.string.cancel,
                        (dialog, which) -> resultOpen = false)
                .setPositiveButton(R.string.qr_cloud_delete, (dialog, which) -> {
                    cloudRuleStore.delete();
                    refreshCloudStatus();
                    resultOpen = false;
                })
                .setOnCancelListener(dialog -> resultOpen = false)
                .show();
    }

    private void refreshCloudStatus() {
        CloudRuleStore.Stats stats = cloudRuleStore.getStats();
        if (stats.isDownloaded()) {
            String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(stats.updatedAt);
            cloudStatus.setText(getString(R.string.qr_cloud_status,
                    stats.acceptedCount, stats.rejectedCount, time));
            cloudUpdateButton.setText(R.string.qr_cloud_update);
            cloudDeleteButton.setVisibility(View.VISIBLE);
        } else {
            cloudStatus.setText(R.string.qr_cloud_not_downloaded);
            cloudUpdateButton.setText(R.string.qr_cloud_download);
            cloudDeleteButton.setVisibility(View.GONE);
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("wallet-qr-camera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        HandlerThread thread = cameraThread;
        cameraThread = null;
        cameraHandler = null;
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void openCamera() {
        if (!cameraActive || cameraOpening || cameraDevice != null || cameraHandler == null
                || !previewView.isAvailable()
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        CameraManager manager = getSystemService(CameraManager.class);
        if (manager == null) {
            showCameraError();
            return;
        }
        try {
            String cameraId = findBackCamera(manager);
            if (cameraId == null) {
                showCameraError();
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation == null ? 90 : orientation;
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                showCameraError();
                return;
            }
            previewSize = chooseAnalysisSize(
                    map.getOutputSizes(ImageFormat.YUV_420_888),
                    map.getOutputSizes(SurfaceTexture.class),
                    previewView.getWidth(), previewView.getHeight());
            configurePreviewTransform(previewView.getWidth(), previewView.getHeight());
            cameraOpening = true;
            final int generation = cameraGeneration;
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    if (generation != cameraGeneration) {
                        camera.close();
                        return;
                    }
                    cameraOpening = false;
                    if (!cameraActive) {
                        camera.close();
                        return;
                    }
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    if (generation != cameraGeneration) {
                        camera.close();
                        return;
                    }
                    cameraOpening = false;
                    camera.close();
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    if (generation != cameraGeneration) {
                        camera.close();
                        return;
                    }
                    cameraOpening = false;
                    camera.close();
                    if (cameraDevice == camera) {
                        cameraDevice = null;
                    }
                    runOnUiThread(() -> showCameraError());
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            cameraOpening = false;
            showCameraError();
        }
    }

    private String findBackCamera(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        return null;
    }

    private Size chooseAnalysisSize(Size[] analysisSizes, Size[] textureSizes,
            int viewWidth, int viewHeight) {
        if (analysisSizes == null || analysisSizes.length == 0) {
            return new Size(1280, 720);
        }

        int relativeRotation = getRelativeCameraRotation();
        boolean swapped = relativeRotation == 90 || relativeRotation == 270;
        double targetAspect = viewWidth > 0 && viewHeight > 0
                ? (swapped ? (double) viewHeight / viewWidth
                        : (double) viewWidth / viewHeight)
                : 16.0 / 9.0;

        Size fallback = null;
        Size best = null;
        double bestAspectDifference = Double.MAX_VALUE;
        long bestArea = 0L;
        for (Size size : analysisSizes) {
            if (!containsSize(textureSizes, size)) {
                continue;
            }
            long area = (long) size.getWidth() * size.getHeight();
            if (fallback == null
                    || area < (long) fallback.getWidth() * fallback.getHeight()) {
                fallback = size;
            }
            if (size.getWidth() <= 1280 && size.getHeight() <= 1280
                    && area >= 640L * 480L) {
                double aspect = (double) size.getWidth() / size.getHeight();
                double difference = Math.abs(aspect - targetAspect);
                if (difference < bestAspectDifference
                        || (difference == bestAspectDifference && area > bestArea)) {
                    best = size;
                    bestAspectDifference = difference;
                    bestArea = area;
                }
            }
        }
        if (best != null) {
            return best;
        }
        return fallback == null ? analysisSizes[0] : fallback;
    }

    private boolean containsSize(Size[] sizes, Size target) {
        if (sizes == null) {
            return false;
        }
        for (Size size : sizes) {
            if (size.equals(target)) {
                return true;
            }
        }
        return false;
    }

    private void createCaptureSession() {
        CameraDevice camera = cameraDevice;
        SurfaceTexture texture = previewView.getSurfaceTexture();
        Handler handler = cameraHandler;
        if (camera == null || texture == null || handler == null || previewSize == null) {
            return;
        }
        try {
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(texture);
            imageReader = ImageReader.newInstance(previewSize.getWidth(), previewSize.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            imageReader.setOnImageAvailableListener(this::onImageAvailable, handler);

            CaptureRequest.Builder request = camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(previewSurface);
            request.addTarget(imageReader.getSurface());
            request.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            request.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);

            camera.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                session.close();
                                return;
                            }
                            captureSession = session;
                            repeatingRequest = request.build();
                            try {
                                session.setRepeatingRequest(repeatingRequest, null, cameraHandler);
                            } catch (CameraAccessException e) {
                                runOnUiThread(() -> showCameraError());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            runOnUiThread(() -> showCameraError());
                        }
                    }, handler);
        } catch (CameraAccessException e) {
            showCameraError();
        }
    }

    private void closeCamera() {
        cameraGeneration++;
        cameraOpening = false;
        CameraCaptureSession session = captureSession;
        captureSession = null;
        if (session != null) {
            session.close();
        }
        CameraDevice camera = cameraDevice;
        cameraDevice = null;
        if (camera != null) {
            camera.close();
        }
        ImageReader reader = imageReader;
        imageReader = null;
        if (reader != null) {
            reader.close();
        }
        Surface surface = previewSurface;
        previewSurface = null;
        if (surface != null) {
            surface.release();
        }
        repeatingRequest = null;
        decodeInFlight = false;
    }

    private void onImageAvailable(ImageReader reader) {
        final Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (resultOpen || decodeInFlight || now - lastDecodeAt < DECODE_INTERVAL_MS) {
            image.close();
            return;
        }
        lastDecodeAt = now;
        decodeInFlight = true;

        final int width = image.getWidth();
        final int height = image.getHeight();
        final byte[] luminance;
        try {
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            luminance = new byte[width * height];
            int base = buffer.position();
            for (int y = 0; y < height; y++) {
                int row = base + y * rowStride;
                for (int x = 0; x < width; x++) {
                    luminance[y * width + x] = buffer.get(row + x * pixelStride);
                }
            }
        } finally {
            image.close();
        }

        DECODE_EXECUTOR.execute(() -> decodeFrame(luminance, width, height));
    }

    private void decodeFrame(byte[] luminance, int width, int height) {
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS,
                Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
        String payload = null;
        PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                luminance, width, height, 0, 0, width, height, false);
        try {
            Result result = reader.decodeWithState(
                    new BinaryBitmap(new HybridBinarizer(source)));
            payload = result.getText();
        } catch (Exception firstFailure) {
            try {
                Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(
                        new InvertedLuminanceSource(source))));
                payload = result.getText();
            } catch (Exception ignored) {
            }
        } finally {
            reader.reset();
            decodeInFlight = false;
        }
        if (payload != null && QrRouting.normalizePayload(payload) != null && !resultOpen) {
            String decoded = payload;
            resultOpen = true;
            runOnUiThread(() -> handleDecodedPayload(decoded));
        }
    }

    private void handleDecodedPayload(String payload) {
        QrRouting.Match match = QrRouting.classify(this, payload);
        if (match.isPayment()) {
            Intent intent = new Intent(this, QrPaymentActivity.class);
            intent.putExtra(QrPaymentActivity.EXTRA_PAYLOAD, match.payload);
            startActivity(intent);
            return;
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.qr_not_payment_title)
                .setMessage(shortPayload(payload))
                .setNegativeButton(R.string.qr_scan_again,
                        (unused, which) -> resultOpen = false)
                .setNeutralButton(R.string.copy, (unused, which) -> {
                    ClipboardManager clipboard = getSystemService(ClipboardManager.class);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("QR", payload));
                    }
                    resultOpen = false;
                })
                .setOnCancelListener(unused -> resultOpen = false);
        if (QrRouting.isSafeWebUri(payload)) {
            dialog.setPositiveButton(R.string.qr_open_browser, (unused, which) -> {
                try {
                    Intent viewIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(payload));
                    Intent chooser = Intent.createChooser(viewIntent,
                            getString(R.string.qr_open_browser));
                    chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS,
                            new ComponentName[]{new ComponentName(
                                    getPackageName(),
                                    "com.mipay.wallet.ui.QrPaymentLinkActivity")});
                    startActivity(chooser);
                } catch (Exception e) {
                    Toast.makeText(this, R.string.no_browser, Toast.LENGTH_SHORT).show();
                }
                resultOpen = false;
            });
        }
        dialog.show();
    }

    private void configurePreviewTransform(int viewWidth, int viewHeight) {
        if (previewSize == null || viewWidth == 0 || viewHeight == 0) {
            return;
        }
        int displayRotation = getDisplay() == null ? Surface.ROTATION_0
                : getDisplay().getRotation();
        Matrix matrix = new Matrix();
        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        if (displayRotation == Surface.ROTATION_90
                || displayRotation == Surface.ROTATION_270) {
            // This is the same view-to-buffer transform used by Xiaomi Scanner. Camera2's
            // TextureView already accounts for the sensor orientation, so rotating by
            // SENSOR_ORIENTATION here would rotate the preview a second time.
            RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
            RectF bufferRect = new RectF(0, 0,
                    previewSize.getHeight(), previewSize.getWidth());
            bufferRect.offset(centerX - bufferRect.centerX(),
                    centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90f * (displayRotation - 2), centerX, centerY);
        } else {
            // The activity is portrait-locked. Correct TextureView's default non-uniform
            // full-screen scaling with a centered crop so circles stay circular.
            int relativeRotation = getRelativeCameraRotation();
            boolean swapped = relativeRotation == 90 || relativeRotation == 270;
            float displayedWidth = swapped
                    ? previewSize.getHeight() : previewSize.getWidth();
            float displayedHeight = swapped
                    ? previewSize.getWidth() : previewSize.getHeight();
            float previewAspect = displayedWidth / displayedHeight;
            float viewAspect = (float) viewWidth / viewHeight;
            float scaleX = previewAspect > viewAspect ? previewAspect / viewAspect : 1f;
            float scaleY = previewAspect < viewAspect ? viewAspect / previewAspect : 1f;
            matrix.setScale(scaleX, scaleY, centerX, centerY);
            if (displayRotation == Surface.ROTATION_180) {
                matrix.postRotate(180f, centerX, centerY);
            }
        }
        previewView.setTransform(matrix);
    }

    private int getRelativeCameraRotation() {
        int displayRotation = getDisplay() == null ? Surface.ROTATION_0
                : getDisplay().getRotation();
        int displayDegrees;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                displayDegrees = 90;
                break;
            case Surface.ROTATION_180:
                displayDegrees = 180;
                break;
            case Surface.ROTATION_270:
                displayDegrees = 270;
                break;
            default:
                displayDegrees = 0;
        }
        return (sensorOrientation - displayDegrees + 360) % 360;
    }

    private void showCameraError() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        Toast.makeText(this, R.string.qr_camera_error, Toast.LENGTH_LONG).show();
    }

    private Button actionButton(int textRes) {
        Button button = new Button(this);
        button.setText(textRes);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(Color.WHITE);
        button.setBackground(ripple(0xff3a7afe, 14));
        return button;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private RippleDrawable ripple(int color, int radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x44ffffff),
                rounded(color, radiusDp), null);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private String shortPayload(String payload) {
        return payload.length() <= 600 ? payload : payload.substring(0, 600) + "…";
    }

    private void configureSystemBars() {
        WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
        if (controller != null) {
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(0, light);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ScannerOverlay extends View {
        private final Paint shade = new Paint();
        private final Paint border = new Paint();

        ScannerOverlay(Context context) {
            super(context);
            shade.setColor(0x55000000);
            border.setColor(0xffffffff);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(3));
            border.setStrokeCap(Paint.Cap.ROUND);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float size = Math.min(getWidth() * 0.72f, dp(300));
            float left = (getWidth() - size) / 2f;
            float top = Math.max(dp(120), (getHeight() - size) / 2f - dp(40));
            RectF frame = new RectF(left, top, left + size, top + size);
            canvas.drawRect(0, 0, getWidth(), frame.top, shade);
            canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), shade);
            canvas.drawRect(0, frame.top, frame.left, frame.bottom, shade);
            canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, shade);

            float corner = dp(34);
            canvas.drawLine(frame.left, frame.top, frame.left + corner, frame.top, border);
            canvas.drawLine(frame.left, frame.top, frame.left, frame.top + corner, border);
            canvas.drawLine(frame.right - corner, frame.top, frame.right, frame.top, border);
            canvas.drawLine(frame.right, frame.top, frame.right, frame.top + corner, border);
            canvas.drawLine(frame.left, frame.bottom - corner, frame.left, frame.bottom, border);
            canvas.drawLine(frame.left, frame.bottom, frame.left + corner, frame.bottom, border);
            canvas.drawLine(frame.right, frame.bottom - corner, frame.right, frame.bottom, border);
            canvas.drawLine(frame.right - corner, frame.bottom, frame.right, frame.bottom, border);
        }
    }
}
