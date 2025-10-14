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
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.content.ContextCompat;
import androidx.core.content.PermissionChecker;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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


    private ActivityResultLauncher<String[]> activityResultLauncher =
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
        mjpegServer = new MJPEGServer(3014);
        try {
            mjpegServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            Log.d(TAG, "MJPEG server started on port 3014");
        } catch(IOException e) {
            Log.e(TAG, "An error occured " + e.getMessage());
        }

        EdgeToEdge.enable(this);
        setContentView(viewBinding.getRoot());
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }
        cameraExecutor = Executors.newSingleThreadExecutor();
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

    public byte[] rotateJPEG(byte[] jpegBytes, int rotationDegrees) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        rotated.compress(Bitmap.CompressFormat.JPEG, 80, out);
        return out.toByteArray();
    }


    public void sendFrameToSocket(byte[] jpegBytes) {
        // Send jpeg bytes to socket
        new Thread(() -> {
           try{
               if (clientOut != null) {
                   Log.d(TAG, "Client accepted");
                   int length = jpegBytes.length;
                   clientOut.write((length >> 24) & 0xFF);
                   clientOut.write((length >> 16) & 0xFF);
                   clientOut.write((length >> 8) & 0xFF);
                   clientOut.write(length & 0xFF);

                   clientOut.write(jpegBytes);
                   clientOut.flush();
               }
           } catch(IOException e ) {
               Log.e(TAG, "Error: " + e.getMessage());
               try {
                   clientOut.close();
               } catch (IOException ex) {
                   throw new RuntimeException(ex);
               }
           }
        }).start();
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
            ProcessCameraProvider cameraProvider;
            try {
                cameraProvider = cameraProviderFuture.get();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());
            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
            startServer();
            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .build();
            imageAnalysis.setAnalyzer(cameraExecutor, imageProxy -> {
               byte[] jpegBytes = convertYUVToJPEG(imageProxy);
                int rotation = imageProxy.getImageInfo().getRotationDegrees();
                jpegBytes = rotateJPEG(jpegBytes, rotation);
                if (mjpegServer != null) {
                    mjpegServer.setLatestFrame(jpegBytes);
                }
                imageProxy.close();
            });
            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,cameraSelector,preview, imageAnalysis);
            } catch(Exception e) {
                Log.e(TAG, "Use case binding failed", e);
            }
        }, ContextCompat.getMainExecutor(this));
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