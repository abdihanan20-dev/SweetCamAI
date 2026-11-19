package com.sweetcam.ai;

import android.Manifest;
import android.app.Activity;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.content.Context;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.*;
import android.graphics.Color;
import android.util.Size;
import java.util.Arrays;
import java.util.Collections;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private TextureView preview;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader imageReader;
    private HandlerThread thread;
    private Handler handler;
    private String cameraId;
    private Size photoSize;

    private final int REQUEST_CAMERA = 100;

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                -1, -1));

        Button shutter = new Button(this);
        shutter.setText("●");
        shutter.setTextSize(30);
        shutter.setTextColor(Color.WHITE);
        shutter.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                        Color.rgb(30, 30, 30)));

        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                180, 150);
        bp.gravity = android.view.Gravity.BOTTOM |
                     android.view.Gravity.CENTER_HORIZONTAL;
        bp.bottomMargin = 35;
        root.addView(shutter, bp);

        TextView title = new TextView(this);
        title.setText("SWEETCAM AI");
        title.setTextColor(Color.WHITE);
        title.setTextSize(19);
        title.setPadding(25, 35, 10, 10);

        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(
                -1, -2);
        tp.gravity = android.view.Gravity.TOP;
        root.addView(title, tp);

        setContentView(root);

        shutter.setOnClickListener(v -> takePhoto());

        preview.setSurfaceTextureListener(
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                    SurfaceTexture s, int w, int h) {
                    checkPermissionAndOpen();
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                    SurfaceTexture s, int w, int h) {}

                @Override
                public boolean onSurfaceTextureDestroyed(
                    SurfaceTexture s) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(
                    SurfaceTexture s) {}
            });
    }

    private void checkPermissionAndOpen() {
        boolean cameraGranted =
            checkSelfPermission(Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED;

        boolean storageGranted =
            Build.VERSION.SDK_INT > 28 ||
            checkSelfPermission(
                Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED;

        if (!cameraGranted || !storageGranted) {
            if (Build.VERSION.SDK_INT >= 23) {
                if (Build.VERSION.SDK_INT <= 28) {
                    requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQUEST_CAMERA);
                } else {
                    requestPermissions(new String[]{
                        Manifest.permission.CAMERA
                    }, REQUEST_CAMERA);
                }
            }
            return;
        }

        startCamera();
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(
            requestCode, permissions, results);

        if (requestCode == REQUEST_CAMERA) {
            if (results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissionAndOpen();
            } else {
                Toast.makeText(this,
                    "Izin kamera diperlukan",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        if (thread != null) return;

        thread = new HandlerThread("SweetCamThread");
        thread.start();
        handler = new Handler(thread.getLooper());

        try {
            CameraManager manager =
                (CameraManager) getSystemService(
                    Context.CAMERA_SERVICE);

            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics c =
                    manager.getCameraCharacteristics(id);

                Integer facing = c.get(
                    CameraCharacteristics.LENS_FACING);

                if (facing != null &&
                    facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    break;
                }
            }

            if (cameraId == null) {
                throw new Exception("Kamera belakang tidak ditemukan");
            }

            CameraCharacteristics characteristics =
                manager.getCameraCharacteristics(cameraId);

            android.hardware.camera2.params.StreamConfigurationMap map =
                characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            if (map == null) {
                throw new Exception("Konfigurasi kamera tidak tersedia");
            }

            Size[] sizes = map.getOutputSizes(
                android.graphics.ImageFormat.JPEG);

            photoSize = sizes[0];

            for (Size size : sizes) {
                long pixels = (long) size.getWidth() * size.getHeight();
                long selected = (long) photoSize.getWidth() *
                                photoSize.getHeight();

                if (pixels <= 16000000 && pixels > selected) {
                    photoSize = size;
                }
            }

            imageReader = ImageReader.newInstance(
                photoSize.getWidth(),
                photoSize.getHeight(),
                android.graphics.ImageFormat.JPEG,
                2);

            imageReader.setOnImageAvailableListener(reader -> {
                Image image = null;
                try {
                    image = reader.acquireNextImage();
                    if (image != null) {
                        java.nio.ByteBuffer buffer =
                            image.getPlanes()[0].getBuffer();

                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        savePhoto(bytes);
                    }
                } catch (Exception e) {
                    runOnUiThread(() ->
                        Toast.makeText(this,
                            "Gagal menyimpan foto",
                            Toast.LENGTH_SHORT).show());
                } finally {
                    if (image != null) image.close();
                }
            }, handler);

            if (checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
                manager.openCamera(cameraId,
                    new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(CameraDevice c) {
                            camera = c;
                            createPreview();
                        }

                        @Override
                        public void onDisconnected(CameraDevice c) {
                            c.close();
                            camera = null;
                        }

                        @Override
                        public void onError(CameraDevice c, int error) {
                            c.close();
                            camera = null;
                            runOnUiThread(() ->
                                Toast.makeText(MainActivity.this,
                                    "Kamera error: " + error,
                                    Toast.LENGTH_LONG).show());
                        }
                    }, handler);
            }

        } catch (Exception e) {
            Toast.makeText(this,
                "Gagal membuka kamera: " + e.getMessage(),
                Toast.LENGTH_LONG).show();
        }
    }

    private void createPreview() {
        try {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture == null || camera == null) return;

            texture.setDefaultBufferSize(1920, 1080);
            Surface surface = new Surface(texture);

            previewBuilder = camera.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW);

            previewBuilder.addTarget(surface);
            previewBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            camera.createCaptureSession(
                Arrays.asList(surface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession s) {
                        session = s;
                        try {
                            session.setRepeatingRequest(
                                previewBuilder.build(),
                                null, handler);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onConfigureFailed(
                        CameraCaptureSession s) {
                        runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                "Gagal membuat preview",
                                Toast.LENGTH_LONG).show());
                    }
                }, handler);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void takePhoto() {
        if (camera == null || session == null) {
            Toast.makeText(this,
                "Kamera belum siap",
                Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            CaptureRequest.Builder builder =
                camera.createCaptureRequest(
                    CameraDevice.TEMPLATE_STILL_CAPTURE);

            builder.addTarget(imageReader.getSurface());
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON);

            builder.set(
                CaptureRequest.JPEG_ORIENTATION, 90);

            session.capture(builder.build(),
                new CameraCaptureSession.CaptureCallback() {
                    @Override
                    public void onCaptureCompleted(
                        CameraCaptureSession s,
                        CaptureRequest r,
                        TotalCaptureResult result) {
                        runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                "Foto diambil",
                                Toast.LENGTH_SHORT).show());
                    }
                }, handler);

        } catch (Exception e) {
            Toast.makeText(this,
                "Gagal mengambil foto",
                Toast.LENGTH_SHORT).show();
        }
    }

    private void savePhoto(byte[] data) {
        OutputStream output = null;

        try {
            String name = "SweetCam_" +
                System.currentTimeMillis() + ".jpg";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

            if (Build.VERSION.SDK_INT >= 29) {
                values.put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "Pictures/SweetCamAI");
            }

            android.net.Uri uri = getContentResolver().insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                values);

            if (uri == null) throw new Exception("Gagal membuat file");

            output = getContentResolver().openOutputStream(uri);
            if (output == null) throw new Exception("Gagal membuka file");

            output.write(data);
            output.flush();

            runOnUiThread(() ->
                Toast.makeText(this,
                    "Foto tersimpan di Pictures/SweetCamAI",
                    Toast.LENGTH_LONG).show());

        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                if (output != null) output.close();
            } catch (Exception ignored) {}
        }
    }

    private void closeCamera() {
        if (session != null) {
            session.close();
            session = null;
        }

        if (camera != null) {
            camera.close();
            camera = null;
        }

        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }

        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join();
            } catch (InterruptedException ignored) {}
            thread = null;
            handler = null;
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview != null && preview.isAvailable()) {
            checkPermissionAndOpen();
        }
    }
}
