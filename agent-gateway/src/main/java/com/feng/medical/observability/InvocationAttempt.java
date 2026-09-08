package com.feng.medical.observability;

import java.time.Instant;
import java.util.UUID;
public record InvocationAttempt(UUID invocationId, int attemptNo, String purpose, String provider, String model,
                                Long inputTokens, Long outputTokens, UsageSource usageSource, Long latencyMs,
                                String status, String errorCode, Instant createdAt) { }
