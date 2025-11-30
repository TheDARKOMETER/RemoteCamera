package com.example.remotecamera.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.remotecamera.HttpHandler.MJPEGServer;
import com.example.remotecamera.Interface.IStreamable;
import com.example.remotecamera.ServiceCallback.UIPublisher;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/* Starts FG Service that runs instance of MJPEG Server */
public class MJPEGWebService extends Service implements IStreamable {

    private int port;
    private MJPEGServer mjpegServer;
    private static final String CHANNEL_ID = "MJGPEGWebServerForegroundChannel";
    private static final String TAG = "WEBFGService";
    private final IBinder binder = new WebBinder();

    private final UIPublisher uiPublisher = UIPublisher.getUIPublisherInstance();


    public class WebBinder extends Binder { public MJPEGWebService getService() {return MJPEGWebService.this; }}

    @Override
    public Context getContext() {
        return getApplicationContext();
    }


    @Override
    public boolean isStreaming() {
        return CameraStreamService.isStreaming;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MJPEGWebService onCreate called");
        startForegroundServiceNotification();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Onstart Command called for MJPEGWebService");
        if (intent != null) {
            port = intent.getIntExtra("port", 3014);
        }
        Log.d(TAG, "Starting web service on port" + port);
        mjpegServer = new MJPEGServer(port, this);
        try {
            Log.d(TAG, "Starting web service on port" + port);
            mjpegServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (IOException e) {
            Log.e(TAG, "Exception occurred at MJPEGWebService", e);
        }
        return START_REDELIVER_INTENT;
    }

    private void startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Web Server", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager!=null)  manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Web Server Running")
                .setContentText("Web Server for Client is running at port " + port)
                .build();

        startForeground(2, notification);
    }

    public void sendFrameToServer(byte[] frame) throws IOException {
        mjpegServer.setLatestFrame(frame);
    }



    public void stop() {
        mjpegServer.stop();
    }
}
