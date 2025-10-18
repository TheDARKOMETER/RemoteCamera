package com.example.remotecamera.HttpHandler;

import android.content.Context;
import android.util.Log;

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
    }

    // Called from MainActivity whenever a new JPEG frame is ready
    public void setLatestFrame(byte[] frame) {
        latestFrame = frame;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri.equals("/")) {
            return serveHTMLPage();
        } else if (uri.equals("/stream")) {
            return serveMJPEGStream();
        } else {
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
        htmlStream.close();
        String html = new String(buffer);




    } catch (IOException e) {
        Log.e(TAG, "Failed to read HTML page: " + e.getMessage());
        return newFixedLengthResponse("Failed to load page");
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
