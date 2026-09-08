package com.feng.medical.auth;

public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("invalid username or password");
    }
}
