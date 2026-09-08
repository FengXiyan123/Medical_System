package com.feng.medical.auth;

public class InvalidRefreshTokenException extends RuntimeException {
    public InvalidRefreshTokenException() {
        super("invalid refresh token");
    }
}
