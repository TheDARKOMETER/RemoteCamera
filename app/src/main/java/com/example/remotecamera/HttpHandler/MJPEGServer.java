package com.example.remotecamera.HttpHandler;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import fi.iki.elonen.NanoHTTPD;

public class MJPEGServer extends NanoHTTPD {

    private static final String TAG = "MJPEGServer";

    private volatile byte[] latestFrame;
    private Context context;

    public MJPEGServer(int port, Context context) {
        super(port);
        this.context = context;
    }

    // Called from MainActivity whenever a new JPEG frame is ready
    public void setLatestFrame(byte[] frame) {
        latestFrame = frame;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.equals("/")) {
            return serveHTMLPage(session);
        } else if (uri.equals("/stream")) {
            return serveMJPEGStream();
        } else if (uri.equals("/mjpeg_style.css")) {
            return serveCSS(session);
        }
        else {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not found");
        }
    }

    private Response serveHTMLPage(IHTTPSession session) {
    try {
        InputStream htmlStream = context.getResources().openRawResource(
                context.getResources().getIdentifier("mjpeg_page", "raw", context.getPackageName()));

        int size = htmlStream.available();
        byte[] buffer = new byte[size];
        htmlStream.read(buffer);
        String html = new String(buffer);
        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    } catch (IOException e) {
        Log.e(TAG, "Failed to read HTML page: " + e.getMessage());
        return newFixedLengthResponse("Failed to load page");
    }
   }

   private Response serveCSS(IHTTPSession session) {
        try {
            InputStream cssStream = context.getResources().openRawResource(
                    context.getResources().getIdentifier("mjpeg_style", "raw", context.getPackageName()));

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;

            while ((read = cssStream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }

            String css = baos.toString("UTF-8");
            cssStream.close();
            baos.close();
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
                    byte[] frame = latestFrame;
                    if (frame == null) {
                        Thread.sleep(10); // wait for first frame, prevents Busy waiting
                        continue;
                    }

                    // Write MJPEG frame
                    pipedOut.write(("--frame\r\n").getBytes());
                    pipedOut.write("Content-Type: image/jpeg\r\n\r\n".getBytes());
                    pipedOut.write(frame);
                    pipedOut.write("\r\n".getBytes());
                    pipedOut.flush();

                    Thread.sleep(33); // ~30 FPS
                }
            } catch (IOException | InterruptedException e) {
                Log.d(TAG, "Client disconnected");
                try { pipedOut.close(); } catch (IOException ex) { /* ignore */ }
            }
        }).start();

        return newChunkedResponse(Response.Status.OK,
                "multipart/x-mixed-replace; boundary=frame", pipedIn);
    }
}
