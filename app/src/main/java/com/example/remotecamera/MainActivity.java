package com.example.remotecamera;

import android.Manifest;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.YuvImage;
import android.icu.text.SimpleDateFormat;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;

import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;


import com.example.remotecamera.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.remotecamera.HttpHandler.MJPEGServer;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding viewBinding;
    private static final String TAG = "RemoteCameraApp";
    private static final String[] REQUIRED_PERMISSIONS;
    private ExecutorService cameraExecutor;
    private static MJPEGServer mjpegServer;
    private ProcessCameraProvider cameraProvider;
    private boolean isStreaming = false;


    private final ActivityResultLauncher<String[]> activityResultLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    new ActivityResultCallback<Map<String, Boolean>>() {
                        @Override
                        public void onActivityResult(Map<String, Boolean> permissions) {
                            boolean permissionGranted = true;
                            for (Map.Entry<String,Boolean> entry : permissions.entrySet()) {
                                String key = entry.getKey();
                                Boolean value = entry.getValue();
                                if (Arrays.asList(REQUIRED_PERMISSIONS).contains(key) && !value) {
                                    permissionGranted = false;
                                    break;
                                }
                            }
                            if (!permissionGranted) {
                                Toast.makeText(getBaseContext(),
                                        "Permission request denied",
                                        Toast.LENGTH_SHORT).show();
                            } else {
                                startCamera();
                            }
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        mjpegServer = new MJPEGServer(3014, this);
        try {
            mjpegServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.d(TAG, "MJPEG server started on port 3014");
            mjpegServer.setLatestFrame(null);
        } catch(IOException e) {
            Log.e(TAG, "An error occured " + e.getMessage());
        }
        EdgeToEdge.enable(this);
        setContentView(viewBinding.getRoot());
        cameraExecutor = Executors.newSingleThreadExecutor();
        if (!allPermissionsGranted()) {
            requestPermissions();
        } else {
            startCamera();
        }
        viewBinding.streamButton.setOnClickListener((e) -> {
            try {
                toggleStream();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    public byte[] convertYUVToJPEG(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        int width = image.getWidth();
        int height = image.getHeight();

        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();

        byte[] nv21 = new byte[ySize + uSize + vSize];

        yBuffer.get(nv21, 0, ySize);
        int uvPos = ySize;
        for (int i = 0; i < uSize; i++) {
            nv21[uvPos++] = vBuffer.get(i); // V
            nv21[uvPos++] = uBuffer.get(i); // U
        }

        // Convert to JPEG
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, width, height), 80, out);


        return out.toByteArray();
    }

    public byte[] drawInformation(byte[] jpeg) {
        String chargingStatus = "";
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        bitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint();
        paint.setTypeface(Typeface.DEFAULT_BOLD);



        String dateText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date());
        if (isPhoneCharging() > 0) {
            chargingStatus = "Plugged";
        }

        String batteryText = "BAT:" + getBatteryLevel() + "%" + " " + chargingStatus;
        // Outline
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(Color.BLACK);
        canvas.drawText(dateText, 5, 20, paint);
        canvas.drawText(batteryText, 5, 35, paint);
        // Text fill
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        canvas.drawText(dateText, 5, 20, paint);
        canvas.drawText(batteryText, 5, 35, paint);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out);
        return out.toByteArray();
    }

    public int getBatteryLevel() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = getApplicationContext().registerReceiver(null, filter);

        if (batteryStatus != null) {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level != -1 && scale != -1) {
                return (int) ((level * 100f) / (float) scale);
            }
        }
        return -1;
    }

    public int isPhoneCharging() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = getApplicationContext().registerReceiver(null, filter);
        if (batteryStatus != null) {
            return batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        }
        return -1;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }

            bindPreviewUseCase(cameraProvider);

        }, ContextCompat.getMainExecutor(this));
    }

    private void bindPreviewUseCase(ProcessCameraProvider pcp) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            cameraProvider.unbindAll();
            cameraProvider.bindToLifecycle(this,cameraSelector,preview);
        } catch(Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
    }

    public void toggleStream() throws IOException {
        viewBinding.streamButton.setEnabled(false);

        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            byte[] jpegBytes = convertYUVToJPEG(imageProxy);
            if (mjpegServer != null) {
                try {
                    mjpegServer.setLatestFrame(drawInformation(jpegBytes));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            imageProxy.close();
        });

        if (isStreaming) {
            cameraProvider.unbindAll();
            viewBinding.streamButton.setText(R.string.start_stream);
            isStreaming = false;
            mjpegServer.setLatestFrame(null);
            bindPreviewUseCase(cameraProvider);
        } else {
            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            try {
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
                viewBinding.streamButton.setText(R.string.stop_stream);
                isStreaming = true;
            } catch(Exception e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }

        viewBinding.streamButton.setEnabled(true);
    }
    private void requestPermissions() {
        activityResultLauncher.launch(REQUIRED_PERMISSIONS);
    }

    private boolean allPermissionsGranted() {
       for (String permission : REQUIRED_PERMISSIONS) {
           if (ContextCompat.checkSelfPermission(getBaseContext(), permission) != PackageManager.PERMISSION_GRANTED) {
               return false;
           }
       }
       return true;
    }


    public boolean isStreaming() {
        return isStreaming;
    }

    static {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.RECORD_AUDIO);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        REQUIRED_PERMISSIONS = permissions.toArray(new String[0]);
    }



}