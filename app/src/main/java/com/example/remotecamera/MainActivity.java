package com.example.remotecamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
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
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.example.remotecamera.HttpHandler.MJPEGServer;

import fi.iki.elonen.NanoHTTPD;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding viewBinding;
    private static final String TAG = "RemoteCameraApp";
    private static final String[] REQUIRED_PERMISSIONS;
    private ExecutorService cameraExecutor;
    private ServerSocket serverSocket;
    private Socket clientSocket;
    private OutputStream clientOut;
    private static MJPEGServer mjpegServer;
    private ProcessCameraProvider cameraProvider;


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
        mjpegServer = new MJPEGServer(3014, getApplicationContext());
        try {
            mjpegServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.d(TAG, "MJPEG server started on port 3014");
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
            startStream();
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

    private void startServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(3013);
                Log.d(TAG, "SERVER RUNNING ON PORT 3013");
                clientSocket = serverSocket.accept();
                Log.d(TAG, "Client connected: " + clientSocket.getInetAddress());
                clientOut = clientSocket.getOutputStream();
            } catch(Exception e) {
                Log.e(TAG, "Server error: " + e.getMessage() );
            }
        }).start();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }

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

    private void startStream() {
        viewBinding.streamButton.setEnabled(false);
        startServer();
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                .build();
        imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
            byte[] jpegBytes = convertYUVToJPEG(imageProxy);
            if (mjpegServer != null) {
                mjpegServer.setLatestFrame(jpegBytes);
            }
            imageProxy.close();
        });
        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);
        } catch(Exception e) {
            Log.e(TAG, "Use case binding failed", e);
        }
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