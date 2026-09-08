package com.feng.medical.knowledge;

public final class GenerationNotReadyException extends IllegalStateException {
    public GenerationNotReadyException(String message) {
        super(message);
    }
}
