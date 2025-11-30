package com.example.remotecamera.ServiceCallback;


import java.util.ArrayList;
import java.util.List;

public class UIPublisher {
    private static UIPublisher instance;
    private final List<Runnable> subscribers = new ArrayList<>();

    private UIPublisher() {}

    public static synchronized UIPublisher getUIPublisherInstance() {
        if (instance == null) {
            instance = new UIPublisher();
            return instance;
        } else {
            return instance;
        }
    }

    public void subscribe(Runnable callback) {
        subscribers.add(callback);
    }

    public void unsubscribe(Runnable callback) {
        subscribers.removeIf((cb) -> cb.equals(callback));
    }

    public void notifySubscribers() {
        for (Runnable subscriber : subscribers) {
            subscriber.run();
        }
    }
}
