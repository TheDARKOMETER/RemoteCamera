package com.example.remotecamera.Interface;

import android.content.Context;

import java.io.IOException;

public interface IStreamable {
    void toggleStream() throws IOException;
    Context getContext();

    boolean isStreaming();
}
