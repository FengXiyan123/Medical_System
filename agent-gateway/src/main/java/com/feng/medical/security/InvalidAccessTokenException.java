package com.feng.medical.security;

public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException(String message) {
        super(message);
    }
}
