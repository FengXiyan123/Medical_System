package com.feng.medical.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {

    @ExceptionHandler({InvalidCredentialsException.class, InvalidRefreshTokenException.class})
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, String> invalidCredentials(RuntimeException exception) {
        return Map.of("code", "AUTHENTICATION_FAILED", "message", exception.getMessage());
    }
}
