package com.example.remotecamera;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;


import com.example.remotecamera.ServiceCallback.UIPublisher;
import com.example.remotecamera.Services.MJPEGWebService;
import com.example.remotecamera.databinding.ActivityMainBinding;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import com.example.remotecamera.Services.CameraStreamService;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding viewBinding;

    private static final String TAG = "RemoteCameraApp";
    private static final String[] REQUIRED_PERMISSIONS;

    private ProcessCameraProvider cameraProvider;
    private boolean isMinimized = false;
    private final UIPublisher uiPublisher = UIPublisher.getUIPublisherInstance();

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
                                startCameraPreview();
                            }
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Start MJPEG Client Web Server
        startWebService();

        // Inflate layout
        viewBinding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(viewBinding.getRoot());

        // Enable edge-to-edge UI
        EdgeToEdge.enable(this);

        // Request permissions if needed
        if (!allPermissionsGranted()) {
            requestPermissions();
        } else {
            startCameraPreview();
        }

        uiPublisher.subscribe(this::updateUI);
        // Button click to toggle streaming service
        viewBinding.streamButton.setOnClickListener(v -> {
            if (CameraStreamService.isStreaming) {
                stopCameraService();
            } else {
                startCameraService();
            }
        });
    }


    @Override
    protected void onPause() {
        super.onPause();
        isMinimized = true;
        uiPublisher.unsubscribe(this::updateUI);
        if (CameraStreamService.isStreaming) {
            stopCameraService();
            startCameraService();
        }
    }

    private void startCameraService() {
        Log.d(TAG, "Starting camera service");
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        serviceIntent.putExtra("isMinimized", isMinimized);
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(null);

        if (!isMinimized) {
            CameraStreamService.setPreviewSurfaceProvider(getPreviewSurfaceProvider());
            startCameraPreview();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void startWebService() {
        Log.d(TAG, "Main Activity starting Web Service");
        Intent webServiceIntent = new Intent(this, MJPEGWebService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(webServiceIntent);
        } else {
            startService(webServiceIntent);
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void updateUI() {
        if (CameraStreamService.isStreaming) {
            viewBinding.streamButton.setText(R.string.stop_stream);
        } else {
            viewBinding.streamButton.setText(R.string.start_stream);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        isMinimized = false;
        updateUI();
        uiPublisher.subscribe(this::updateUI);
        // Stop and start camera stream service to enable preview upon maximizing app again
        if (CameraStreamService.isStreaming) {
            stopCameraService();
            startCameraService();
        } else {
            startCameraPreview();
        }
    }

    private void stopCameraService() {
        Intent serviceIntent = new Intent(this, CameraStreamService.class);
        stopService(serviceIntent);
        if (!isMinimized) viewBinding.viewFinder.postDelayed(this::startCameraPreview, 50);

    }

    private void startCameraPreview() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Exception occurred", e);
            }
           // bindPreviewUseCase(cameraProvider);
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
        }, ContextCompat.getMainExecutor(this));
    }


//    private void bindPreviewUseCase(ProcessCameraProvider pcp) {
//        Log.d(TAG, "Starting camera and binding preview use case");
//        Preview preview = new Preview.Builder().build();
//        preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());
//        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
//        try {
//            cameraProvider.unbindAll();
//            cameraProvider.bindToLifecycle(this,cameraSelector,preview);
//        } catch(Exception e) {
//            Log.e(TAG, "Use case binding failed", e);
//        }
//    }

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