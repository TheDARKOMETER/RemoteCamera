package com.example.remotecamera.Interface;

import android.content.Context;

public interface IStreamable {

    Context getContext();

    void setFlashlight(boolean state);
    boolean isStreaming();


    boolean getFlashlightState();
}
