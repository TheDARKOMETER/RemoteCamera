package com.example.remotecamera;

import android.Manifest;
import android.content.Context;
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
import android.os.PowerManager;
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
import com.example.remotecamera.Services.CameraStreamService;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding viewBinding;

    private static final String TAG = "RemoteCameraApp";
    private static final String[] REQUIRED_PERMISSIONS;

    private ProcessCameraProvider cameraProvider;


    private final ActivityResultLauncher<String[]> activityResultLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    new ActivityResultCallback<Map<String, Boolean>>() {
                        @Override
                        public void onActivityResult(Map<String, Boolean> permissions) {
                            Log.d(TAG, "Activity result callback called");
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

        // Inflate layout
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        // Enable edge-to-edge UI
        EdgeToEdge.enable(this);

        // Request permissions if needed
        if (!allPermissionsGranted()) {
            requestPermissions();
        } else {
            startCamera();
        }

        // Button click to toggle streaming service
        viewBinding.streamButton.setOnClickListener(v -> {
            if (isStreamingServiceRunning()) {
                stopCameraService();
                viewBinding.streamButton.setText(R.string.start_stream);
            } else {
                startCameraService();
                viewBinding.streamButton.setText(R.string.stop_stream);
            }
        });
    }


    private void startCameraService() {
        Log.d(TAG, "Starting camera service");
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        CameraStreamService.setPreviewSurfaceProvider(getPreviewSurfaceProvider());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopCameraService() {
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        stopService(serviceIntent);
    }

    // Optional: check if the service is already running
    private boolean isStreamingServiceRunning() {
        // You can keep a static boolean in your service:
        return CameraStreamService.isStreaming;
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        Log.d(TAG, "Starting camera and binding preview use case");
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

    public Preview.SurfaceProvider getPreviewSurfaceProvider() {
        return viewBinding.viewFinder.getSurfaceProvider();
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