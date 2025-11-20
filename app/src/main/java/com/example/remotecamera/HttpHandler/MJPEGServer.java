package com.example.remotecamera.HttpHandler;

import android.util.Log;

import com.example.remotecamera.Interface.IStreamable;
import com.example.remotecamera.R;
import com.example.remotecamera.Services.Flashlight;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import fi.iki.elonen.NanoHTTPD;
import java.util.Map;

public class MJPEGServer extends NanoHTTPD {

    private static final String TAG = "MJPEGServer";

    private volatile byte[] latestFrame;

    private final Object frameLock = new Object();
    private final IStreamable streamableContext;
    private Flashlight flashlight;
    public MJPEGServer(int port, IStreamable streamableContext) {
        super(port);
        this.streamableContext = streamableContext;
        flashlight = new Flashlight(streamableContext.getContext());
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
        InputStream noCameraInputStream = streamableContext.getContext().getResources().openRawResource(R.raw.nocamera);
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

        switch (uri) {
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
            case "/flashlight":
                String state = null;
                Map<String, List<String>> params = session.getParameters();
                if (params.containsKey("state")) {
                    state = params.get("state").get(0);
                }
                assert state != null;
                if(state.equalsIgnoreCase("on")) {
                    // method for on flashlight
                    flashlight.setFlashlight(true);
                    return newFixedLengthResponse("Flashlight turned on");
                } else if (state.equalsIgnoreCase("off")) {
                    // method for off flashlight
                    flashlight.setFlashlight(false);
                    return newFixedLengthResponse("Flashlight turned off");
                } else {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, NanoHTTPD.MIME_PLAINTEXT, "Invalid state");
                }
            default:
                return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found");
        }
    }

    private Response serveStatus() {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, Boolean.toString(streamableContext.isStreaming()));
    }



    private Response serveJS() {
        try (InputStream jsStream = streamableContext.getContext().getResources().openRawResource(R.raw.script);){
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
        InputStream htmlStream = streamableContext.getContext().getResources().openRawResource(R.raw.mjpeg_page);
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
            InputStream cssStream = streamableContext.getContext().getResources().openRawResource(
                    streamableContext.getContext().getResources().getIdentifier("mjpeg_style", "raw", streamableContext.getContext().getPackageName()));
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
        response.addHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.addHeader("Pragma", "no-cache");
        response.addHeader("Expires", "0");
        return response;
    }


}

