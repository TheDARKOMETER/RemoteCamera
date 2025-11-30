package com.example.remotecamera.Interface;

import android.content.Context;

import java.io.IOException;

public interface IStreamable {

    Context getContext();

    boolean isStreaming();
}
