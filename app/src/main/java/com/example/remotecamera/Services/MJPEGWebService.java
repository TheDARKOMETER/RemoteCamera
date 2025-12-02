package com.example.remotecamera.Services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.example.remotecamera.HttpHandler.MJPEGServer;
import com.example.remotecamera.Interface.IStreamable;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/* Starts FG Service that runs instance of MJPEG Server */
public class MJPEGWebService extends Service implements IStreamable {

    private int port;
    private MJPEGServer mjpegServer;
    private static final String CHANNEL_ID = "MJGPEGWebServerForegroundChannel";
    private static final String TAG = "WEBFGService";
    private final IBinder binder = new WebBinder();
    private boolean flashlightState = false;


    private final BroadcastReceiver flashlightStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            flashlightState = intent.getBooleanExtra("flashlightStatus", false);
        }
    };

    public class WebBinder extends Binder { public MJPEGWebService getService() {return MJPEGWebService.this; }}

    @Override
    public Context getContext() {
        return getApplicationContext();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public boolean isStreaming() {
        return CameraStreamService.isStreaming;
    }

    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MJPEGWebService onCreate called");
        startForegroundServiceNotification();

        IntentFilter filter = new IntentFilter("com.remotecamera.FLASHLIGHT_STATUS");
        registerReceiver(flashlightStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
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

    @Override
    public void setFlashlight(boolean state) {
        Intent intent = new Intent("com.remotecamera.FLASHLIGHT_ACTION");
        intent.setPackage(getPackageName());  // <-- THIS MAKES IT REACH THE NON-EXPORTED RECEIVERacka
        intent.putExtra("flashlight", state);
        Log.d(TAG, "Trying to send broadcast");
        sendBroadcast(intent);
    }

    @Override
    public boolean getFlashlightState() {
        return flashlightState;
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
