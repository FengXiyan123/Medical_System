package com.feng.medical.streaming;

public interface RunEventBroadcaster {
    void publish(RunEvent event);
}
