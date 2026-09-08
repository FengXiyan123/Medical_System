package com.feng.medical.conversation;

public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() { super("Idempotency-Key 已被用于不同请求"); }
}
