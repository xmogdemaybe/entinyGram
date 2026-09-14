package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float minZoom = 1f;
    private float maxZoom = 1f;
    private float currentZoom = 1f;
    private Range<Float> zoomRatioRange;

    private final Size previewSize;
    private ImageReader imageReader;
    private long lastTime;

    private volatile int currentRecordingFps = 30;
    private boolean recordingVideo;
    private boolean scanningBarcode;
    private boolean nightMode;
    private boolean flashing;
    private boolean opened = false;
    private Runnable doneCallback;
    private final Rect cropRegion = new Rect();

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String id : cameraIds) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null || facing != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null) {
                        Size size = chooseOptimalSize(confMap.getOutputSizes(SurfaceTexture.class), viewWidth, viewHeight, false);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        return new Camera2Session(context, front, cameraId, bestSize);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size) {
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                AndroidUtilities.runOnUIThread(() -> isError = true);
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.d("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    updateCaptureRequest();
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                AndroidUtilities.runOnUIThread(() -> isError = true);
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        this.imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxDigitalZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (maxDigitalZoom == null || maxDigitalZoom < 1f) ? 1f : maxDigitalZoom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                zoomRatioRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zoomRatioRange != null) {
                    minZoom = zoomRatioRange.getLower();
                    maxZoom = zoomRatioRange.getUpper();
                }
            }
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (SecurityException se) {
            FileLog.e("Permission not granted to open camera " + cameraId, se);
            AndroidUtilities.runOnUIThread(() -> isError = true);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> isError = true);
        }
    }

    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        surface = new Surface(surfaceTexture);
        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> isError = true);
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) return 0;
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0: degrees = 0; break;
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) sensorOrientation = 0;
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360;
            } else {
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) return 0;
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0: degrees = 0; break;
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (sensorOrientation == null) sensorOrientation = 0;
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360;
            } else {
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int diffOrientation = getJpegOrientation() - getDisplayOrientation();
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    public void setZoom(float value) {
        currentZoom = Math.max(minZoom, Math.min(value, maxZoom));
        if (!isInitiated() || captureRequestBuilder == null || cameraDevice == null) return;
        updateCaptureRequest();
    }

    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
        }
    }

    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        return minZoom;
    }

    public boolean isFront() {
        return isFront;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public int getRecordingFrameRate() {
        return currentRecordingFps;
    }

    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private void updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return;
        try {
            int template;
            if (recordingVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (scanningBarcode) {
                template = CameraDevice.TEMPLATE_STILL_CAPTURE;
            } else {
                template = CameraDevice.TEMPLATE_PREVIEW;
            }
            captureRequestBuilder = cameraDevice.createCaptureRequest(template);

            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            } else if (nightMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            }

            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

            if (recordingVideo) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectOptimalCfrFpsRange());
                chooseStabilizationMode(captureRequestBuilder);
                chooseFocusMode(captureRequestBuilder);
            }

            if (zoomRatioRange != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom);
            } else if (sensorSize != null && Math.abs(currentZoom - 1f) >= 0.01f) {
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = (int) ((0.5f * sensorSize.width()) / currentZoom);
                final int deltaY = (int) ((0.5f * sensorSize.height()) / currentZoom);
                cropRegion.set(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY);
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), null, handler);
        } catch (Exception e) {
            currentRecordingFps = 30;
            FileLog.e("Camera2Session setRepeatingRequest error in updateCaptureRequest", e);
        }
    }

    private boolean is60FpsToggleEnabled() {
        try {
            return desu.inugram.InuConfig.ROUND_CAMERA_60FPS.getValue();
        } catch (Throwable t) {
            try {
                Class<?> clazz = Class.forName("desu.inugram.InuConfig");
                Object item = clazz.getField("ROUND_CAMERA_60FPS").get(null);
                return (boolean) item.getClass().getMethod("getValue").invoke(item);
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    @NonNull
    private Range<Integer> selectOptimalCfrFpsRange() {
        if (cameraCharacteristics == null) {
            currentRecordingFps = 30;
            return new Range<>(30, 30);
        }
        Range<Integer>[] availableRanges = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (availableRanges == null || availableRanges.length == 0) {
            currentRecordingFps = 30;
            return new Range<>(30, 30);
        }

        // Если тумблер включен в настройках форка:
        if (is60FpsToggleEnabled()) {
            for (Range<Integer> range : availableRanges) {
                if (range != null && range.getLower() == 60 && range.getUpper() == 60) {
                    currentRecordingFps = 60;
                    FileLog.d("Camera2Session: hardware [60, 60] CFR selected");
                    return range;
                }
            }
        }

        // Чистые 30 FPS CFR (без рассинхрона PTS)
        for (Range<Integer> range : availableRanges) {
            if (range != null && range.getLower() == 30 && range.getUpper() == 30) {
                currentRecordingFps = 30;
                FileLog.d("Camera2Session: hardware [30, 30] CFR selected");
                return range;
            }
        }

        currentRecordingFps = 30;
        return new Range<>(30, 30);
    }

    private void chooseStabilizationMode(CaptureRequest.Builder builder) {
        if (cameraCharacteristics == null) return;
        final int[] ois = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (ois != null) {
            for (int mode : ois) {
                if (mode == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) {
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
                    return;
                }
            }
        }
        final int[] eis = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        if (eis != null) {
            for (int mode : eis) {
                if (mode == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) {
                    builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                    builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF);
                    return;
                }
            }
        }
    }

    private void chooseFocusMode(CaptureRequest.Builder builder) {
        if (cameraCharacteristics == null) return;
        final int[] modes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        if (modes != null) {
            for (int mode : modes) {
                if (mode == CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    return;
                }
            }
        }
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (cameraDevice == null || captureSession == null) return false;
        try {
            CaptureRequest.Builder stillBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            stillBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                FileOutputStream output = null;
                try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (image != null) image.close();
                    if (output != null) {
                        try {
                            output.close();
                        } catch (IOException ignored) {}
                    }
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (whenDone != null) {
                        whenDone.run(orientation);
                    }
                });
            }, handler);

            if (scanningBarcode) {
                stillBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            stillBuilder.addTarget(imageReader.getSurface());
            captureSession.capture(stillBuilder.build(), null, handler);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Session takePicture error", e);
            return false;
        }
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        Runnable cleanupRunnable = () -> {
            if (captureSession != null) {
                try {
                    captureSession.close();
                } catch (Exception ignored) {}
                captureSession = null;
            }
            if (cameraDevice != null) {
                try {
                    cameraDevice.close();
                } catch (Exception ignored) {}
                cameraDevice = null;
            }
            if (imageReader != null) {
                try {
                    imageReader.close();
                } catch (Exception ignored) {}
                imageReader = null;
            }
            if (thread != null) {
                thread.quitSafely();
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        };

        if (async && handler != null) {
            handler.post(cleanupRunnable);
        } else {
            cleanupRunnable.run();
            if (thread != null) {
                try {
                    thread.join();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        if (choices == null || choices.length == 0) return new Size(width, height);
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        for (Size option : choices) {
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * height / width && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (!bigEnoughWithAspectRatio.isEmpty()) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (!bigEnough.isEmpty()) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }

    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}