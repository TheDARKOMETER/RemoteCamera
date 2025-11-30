package com.example.remotecamera.Services;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

public class Flashlight{
    private CameraManager cameraManager;
    private String cameraId;

    private boolean isOn = false;

    public Flashlight(Context context) {
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        try {
            for (String id : cameraManager.getCameraIdList()) {
                Boolean hasFlash = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                if (hasFlash != null && hasFlash) {
                    cameraId = id;
                    break;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void setFlashlight(boolean state) {
        setIsOn(state);
        if (cameraId != null) {
            try {
                cameraManager.setTorchMode(cameraId, state);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean getIsOn() {
        return isOn;
    }

    public void setIsOn(boolean isOn) {
        this.isOn = isOn;
    }
}
