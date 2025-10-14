package com.example.remotecamera.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.remotecamera.HttpHandler.MJPEGServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class CameraStreamService extends Service {
    private static final String CHANNEL_ID = "camera_stream_channel";
    private static final String TAG = "CameraStreamService";
    private ExecutorService cameraExecutor;
    private MJPEGServer mjpegServer;


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Remote Camera Streaming")
                .setContentText("Streaming video over HTTP")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();

        startForeground(1, notification);
        cameraExecutor = Executors.newSingleThreadExecutor();

        //mjpegServer = new MJPEGServer(3014)

    }

    private void startCameraStream() {
        cameraExecutor.execute(() -> {

        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            "Camera Stream Service",
                    NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }
}
