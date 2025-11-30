package com.example.remotecamera.Services;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

public class CameraLifeCycleOwner implements LifecycleOwner {
    private final LifecycleRegistry lifecycleRegistry = new LifecycleRegistry(this);
    
    public CameraLifeCycleOwner() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
    }
    
    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return lifecycleRegistry;
    }
    
    public void start() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
    }
    
    public void stop() {
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
    }
}
