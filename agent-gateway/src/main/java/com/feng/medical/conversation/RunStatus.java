package com.feng.medical.conversation;

public enum RunStatus {
    CREATED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isActive() {
        return this == CREATED || this == RUNNING;
    }
}
