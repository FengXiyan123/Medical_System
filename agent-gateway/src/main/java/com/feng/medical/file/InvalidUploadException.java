package com.feng.medical.file;

public class InvalidUploadException extends RuntimeException {
    public InvalidUploadException(String message) { super(message); }
    public InvalidUploadException(String message, Throwable cause) { super(message, cause); }
}
