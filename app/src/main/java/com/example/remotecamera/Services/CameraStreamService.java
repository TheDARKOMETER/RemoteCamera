package com.example.remotecamera.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
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
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.example.remotecamera.HttpHandler.MJPEGServer;
import com.example.remotecamera.Interface.IStreamable;
import com.example.remotecamera.R;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import fi.iki.elonen.NanoHTTPD;

public class CameraStreamService extends Service {

    private static final String TAG = "CameraFGService";
    private static final String CHANNEL_ID = "CameraForegroundChannel";
    private PowerManager.WakeLock wakeLock;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private CameraLifeCycleOwner lifeCycleOwner;
    public static boolean isStreaming = false;
    private static Preview.SurfaceProvider previewSurfaceProvider;
    private boolean isMinimized = false;

    private MJPEGWebService mjpegWebService;
    private boolean isBound = false;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MJPEGWebService.WebBinder wb = (MJPEGWebService.WebBinder) service;
            mjpegWebService = wb.getService();
            isBound = true;

            // Start camera streaming
            startCameraStreaming();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            mjpegWebService = null;
        }
    };
    public Context getContext() {
        return this;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Keep CPU awake
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::CameraWakeLock");
        wakeLock.acquire(Long.MAX_VALUE);



        // Executor
        cameraExecutor = Executors.newSingleThreadExecutor();
        if (mjpegWebService != null) {
            try {
                mjpegWebService.sendFrameToServer(null);
            } catch (IOException e) {
                Log.e(TAG, "MJPEG Server Frame Send failed: " + e.getMessage());
            }
        }



        // Start foreground notification
        startForegroundServiceNotification();
    }


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            isMinimized = intent.getBooleanExtra("isMinimized", false);
        }
        isStreaming = true;
        Intent webServiceIntent = new Intent(this, MJPEGWebService.class);
        bindService(webServiceIntent, connection, Context.BIND_AUTO_CREATE);
        return START_REDELIVER_INTENT;
    }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camera Streaming",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Camera Streaming Active")
                .setContentText("Your camera is streaming in the background")
                .build();

        startForeground(1, notification);
    }

    private void startCameraStreaming() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                cameraProvider = cameraProviderFuture.get();
                toggleStream();
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraProvider failed", e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    public void toggleStream() throws IOException {
        lifeCycleOwner = new CameraLifeCycleOwner();
        lifeCycleOwner.start();
        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder().build();
        imageAnalysis.setAnalyzer(cameraExecutor, image -> {
            byte[] jpeg = convertYUVToJPEG(image);
            try {
                mjpegWebService.sendFrameToServer(drawInformation(jpeg));
            } catch (IOException e) {
                Log.e(TAG, "Failed to update MJPEG frame", e);
            }
            image.close();
        });

//        if (isStreaming()) {
//            cameraProvider.unbindAll();
//            isStreaming = false;
//            try {
//                mjpegWebService.sendFrameToServer(null);
//            } catch (IOException e) {
//                Log.e(TAG, "Failed to update MJPEG frame", e);
//            }
//        }

        // If activity is stopping, remove preview use case

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
        try {
            cameraProvider.unbindAll();

            if (!isMinimized) {
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewSurfaceProvider);
                cameraProvider.bindToLifecycle(
                        lifeCycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                );
            } else {
                cameraProvider.bindToLifecycle(
                        lifeCycleOwner,
                        cameraSelector,
                        imageAnalysis
                );
            }

            isStreaming = true;
            mjpegWebService.setIsStreaming(true);
        } catch (Exception e) {
            Log.e(TAG, "Camera binding failed", e);
        }
    }

    public static void setPreviewSurfaceProvider(Preview.SurfaceProvider provider) {
        previewSurfaceProvider = provider;
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        isStreaming = false;
        mjpegWebService.setIsStreaming(false);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (lifeCycleOwner != null) lifeCycleOwner.stop();
        if (isBound) unbindService(connection);
        try {
            mjpegWebService.sendFrameToServer(null);
        } catch (IOException e) {
            Log.e(TAG, "Failed to send frame to server", e);
        }
        stopService();
    }


    // Unsure when to use this
    public void stopService() {
        stopForeground(true);
        stopSelf();
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

    public int isPhoneCharging() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent batteryStatus = getApplicationContext().registerReceiver(null, filter);
        if (batteryStatus != null) {
            return batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        }
        return -1;
    }

    public boolean isStreaming() {
        return isStreaming;
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


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not bound
    }
}

