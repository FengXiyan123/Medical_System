package com.feng.medical.conversation;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ConversationExceptionHandler {
    @ExceptionHandler(ResourceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    Map<String, String> notFound(ResourceNotFoundException exception) {
        return Map.of("code", "NOT_FOUND", "message", exception.getMessage());
    }

    @ExceptionHandler({IdempotencyConflictException.class, ActiveRunConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    Map<String, String> conflict(RuntimeException exception) {
        return Map.of("code", "CONFLICT", "message", exception.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Map<String, String> invalid(RuntimeException exception) {
        return Map.of("code", "INVALID_REQUEST", "message", exception.getMessage());
    }
}
