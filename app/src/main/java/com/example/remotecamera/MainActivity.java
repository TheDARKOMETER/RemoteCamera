package com.example.remotecamera;

import android.Manifest;
import android.content.ContentValues;
import android.content.pm.PackageManager;
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

public class MainActivity extends AppCompatActivity {

    private static ActivityMainBinding viewBinding;
    private static final String TAG = "RemoteCameraApp";
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS";
    private static final String[] REQUIRED_PERMISSIONS;
    private ImageCapture imageCapture;
    private VideoCapture<Recorder> videoCapture;
    private Recording recording;
    private ExecutorService cameraExecutor;

    private static class LuminosityAnalyzer implements ImageAnalysis.Analyzer {

        private final LumaListener listener;

        public LuminosityAnalyzer(LumaListener listener) {
            this.listener = listener;
        }

        // Helper function to convert ByteBuffer to byte[]
        private byte[] toByteArray(ByteBuffer buffer) {
            buffer.rewind(); // rewind to zero
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            return data;
        }

        @Override
        public void analyze(@NonNull ImageProxy image) {
            // Get the buffer of the first plane (Y plane for YUV_420_888)
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            byte[] data = toByteArray(buffer);

            // Convert bytes to unsigned int and calculate average
            int[] pixels = new int[data.length];
            for (int i = 0; i < data.length; i++) {
                pixels[i] = data[i] & 0xFF; // convert signed byte to unsigned
            }

            double luma = 0;
            for (int value : pixels) {
                luma += value;
            }
            luma /= pixels.length;

            // Send result to listener
            listener.onLuminosity(luma);

            // Close image to allow next frame
            image.close();
        }


    }

    public interface LumaListener {
        void onLuminosity(double luma);
    }

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
        EdgeToEdge.enable(this);
        setContentView(viewBinding.getRoot());

        if (allPermissionsGranted()) {
            startCamera();
        } else {
            requestPermissions();
        }

        viewBinding.imageCaptureButton.setOnClickListener((v) -> takePhoto());
        viewBinding.videoCaptureButton.setOnClickListener((v) -> captureVideo());

        cameraExecutor = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    private void takePhoto() {

        // Get a stable reference of the modifiable image capture use case
        ImageCapture imageCapture = this.imageCapture;
        if (imageCapture == null) {
            return;
        }

        // Create time stamped name and MediaStore entry.
        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/RemoteCamera-Image");
        }


        // Create output options object which contains file + metadata
        ImageCapture.OutputFileOptions outputFileOptions = new ImageCapture.OutputFileOptions.Builder(
                getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                .build();

        // Set up image capture listener, which is triggered after photo has
        // been taken
        imageCapture.takePicture(
                outputFileOptions,
                ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        String message = "Photo capture succeeded: " + outputFileResults.getSavedUri();
                        Toast.makeText(getBaseContext(), message, Toast.LENGTH_SHORT).show();
                        Log.d(TAG, message);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Photo capture failed: " + exception.getMessage());
                    }
                }
        );

    }
    private void captureVideo() {
        if (videoCapture == null) {
            return;
        }

        viewBinding.videoCaptureButton.setEnabled(false);

        Recording curRecording = recording;
        if (curRecording != null) {
            //Stop the current recording session
            curRecording.stop();
            recording = null;
            return;
        }

        String name = new SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                .format(System.currentTimeMillis());
        ContentValues contentValues = new ContentValues();
        contentValues.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        contentValues.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
            contentValues.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/RemoteCamera-Video");
        }

        MediaStoreOutputOptions mediaStoreOutputOptions = new MediaStoreOutputOptions.Builder(getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(contentValues)
                .build();
        PendingRecording pendingRecording = videoCapture.getOutput()
                .prepareRecording(this, mediaStoreOutputOptions);
        if (PermissionChecker.checkSelfPermission(getBaseContext(), Manifest.permission.RECORD_AUDIO) == PermissionChecker.PERMISSION_GRANTED) {
            pendingRecording.withAudioEnabled();
        }
        recording = pendingRecording.start(ContextCompat.getMainExecutor(this), (recordEvent) -> {
                    if (recordEvent instanceof VideoRecordEvent.Start) {
                        viewBinding.videoCaptureButton.setText(R.string.stop_capture);
                        viewBinding.videoCaptureButton.setEnabled(true);
                    } else if (recordEvent instanceof VideoRecordEvent.Finalize) {
                        if (!((VideoRecordEvent.Finalize) recordEvent).hasError()) {
                            String msg = "Video capture succeeded: " + ((VideoRecordEvent.Finalize) recordEvent).getOutputResults().getOutputUri();
                            Toast.makeText(getBaseContext(), msg, Toast.LENGTH_SHORT).show();
                            Log.d(TAG, msg);
                        } else {
                            recording.close();
                            recording = null;
                            Log.e(TAG, "VIDEO CAPTURE ENDS WITH ERROR: " + ((VideoRecordEvent.Finalize) recordEvent).getError());
                        }
                        viewBinding.videoCaptureButton.setText(R.string.stop_capture);
                        viewBinding.videoCaptureButton.setEnabled(true);
                    }
                });


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

            Recorder recorder = new Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HIGHEST, FallbackStrategy.higherQualityOrLowerThan(Quality.SD)))
                    .build();
            videoCapture = VideoCapture.withOutput(recorder);

            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(viewBinding.viewFinder.getSurfaceProvider());
            imageCapture = new ImageCapture.Builder().build();
            CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

            ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                    .build();
            imageAnalysis.setAnalyzer(cameraExecutor, new LuminosityAnalyzer(new LumaListener() {
                @Override
                public void onLuminosity(double luma) {
                    Log.d(TAG, "Average luminosity: " + luma);
                }
            }));

            try {
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this,cameraSelector,preview, imageCapture, videoCapture);
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