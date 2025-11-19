package com.example.remotecamera.HttpHandler;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.example.remotecamera.MainActivity;
import com.example.remotecamera.R;
import com.example.remotecamera.Services.CameraStreamService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;

public class MJPEGServer extends NanoHTTPD {

    private static final String TAG = "MJPEGServer";

    private volatile byte[] latestFrame;

    private final Object frameLock = new Object();
    private final CameraStreamService context;
    public MJPEGServer(int port, CameraStreamService context) {
        super(port);
        this.context = context;
    }

    // Called from MainActivity whenever a new JPEG frame is ready
    public void setLatestFrame(byte[] frame) throws IOException {
        if (frame != null) {
            synchronized (frameLock) {
                latestFrame = frame;
                frameLock.notifyAll();
            }
        } else {
            latestFrame = getNoCameraImage();
        }
    }

    private byte[] getNoCameraImage() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        InputStream noCameraInputStream = context.getResources().openRawResource(R.raw.nocamera);
        byte[] data = new byte[1024];
        int nData;
        while((nData = noCameraInputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nData);
        }
        return buffer.toByteArray();
    }
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        AtomicBoolean isError = new AtomicBoolean(false);
        switch (uri) {
            case "/toggleStream":
                    try {
                        context.bindCameraUseCase();
                    } catch (IOException e) {
                        isError.set(true);
                        Log.e(TAG, Objects.requireNonNull(e.getMessage()));
                    }
                if (!isError.get()) {
                    return newFixedLengthResponse(Response.Status.OK, "text/plain", "Stream request success");
                } else {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Stream request failed");
                }
            case "/":
                return serveHTMLPage(session);
            case "/stream":
                return serveMJPEGStream();
            case "/mjpeg_style":
                return serveCSS();
            case "/script":
                return serveJS();
            case "/streamStatus":
                return serveStatus();
            default:
                return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found");
        }
    }

    private Response serveStatus() {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, Boolean.toString(context.isStreaming()));
    }



    private Response serveJS() {
        try (InputStream jsStream = context.getResources().openRawResource(R.raw.script);){
            ByteArrayOutputStream jsBao = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ( ( read = jsStream.read(buffer) ) != -1 ) {
                jsBao.write(buffer, 0, read);
            }
            String js = jsBao.toString("UTF-8");
            jsStream.close();
            jsBao.close();
            return newFixedLengthResponse(Response.Status.OK, "application/javascript", js);
        } catch (IOException e) {
            Log.e(TAG, "Failed to return JS script");
            return newFixedLengthResponse("Failed to load JS");
        }
    }

    private Response serveHTMLPage(IHTTPSession session) {
    try {
        InputStream htmlStream = context.getResources().openRawResource(R.raw.mjpeg_page);
        int size = htmlStream.available();
        byte[] buffer = new byte[size];
        htmlStream.read(buffer);
        String html = new String(buffer);
        htmlStream.close();
        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    } catch (IOException e) {
        Log.e(TAG, "Failed to read HTML page: " + e.getMessage());
        return newFixedLengthResponse("Failed to load page");
    }
   }

   private Response serveCSS() {
        try {
            InputStream cssStream = context.getResources().openRawResource(
                    context.getResources().getIdentifier("mjpeg_style", "raw", context.getPackageName()));
            ByteArrayOutputStream baoStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;

            while ((read = cssStream.read(buffer)) != -1) {
                baoStream.write(buffer, 0, read);
            }

            String css = baoStream.toString("UTF-8");
            cssStream.close();
            baoStream.close();
            return newFixedLengthResponse(Response.Status.OK,"text/css", css);
        } catch(IOException e) {
            Log.e(TAG, "Failed to return CSS style" + e.getMessage());
            return newFixedLengthResponse("Failed to load CSS");
        }
   }

    private Response serveMJPEGStream() {
        final PipedOutputStream pipedOut = new PipedOutputStream();
        final PipedInputStream pipedIn;
        try {
            pipedIn = new PipedInputStream(pipedOut, 64 * 1024); // 64KB buffer
        } catch (IOException e) {
            Log.e(TAG, "Failed to create pipe: " + e.getMessage());
            return newFixedLengthResponse("Failed to open stream");
        }

        // Start a background thread to continuously write MJPEG frames
        new Thread(() -> {
            try {
                while (true) {
                    byte[] frameToSend;
                    synchronized(frameLock) {
                        frameToSend = latestFrame != null ? latestFrame : getNoCameraImage();
                    }
                    // Write MJPEG frame
                    pipedOut.write(("--frame\r\n").getBytes());
                    pipedOut.write("Content-Type: image/jpeg\r\n\r\n".getBytes());
                    pipedOut.write(frameToSend);
                    pipedOut.write("\r\n".getBytes());
                    pipedOut.flush();
                    Thread.sleep(33); // ~30 FPS
                }
            } catch (IOException | InterruptedException e) {
                Log.d(TAG, "Client disconnected");
                try { pipedOut.close(); } catch (IOException ex) { /* ignore */ }
            }
        }).start();

        Response response = newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame", pipedIn);
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }


}

