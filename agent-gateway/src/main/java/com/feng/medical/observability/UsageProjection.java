package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One provider invocation attempt. This is the only token-accounting leaf. */
public record UsageProjection(UUID invocationId, int attemptNo, UUID runId, UUID userId, RunMode mode,
                              String purpose, String provider, String model, Long inputTokens, Long outputTokens,
                              UsageSource usageSource, BigDecimal estimatedCost, String currency,
                              String priceSnapshot, Instant observedAt) {
    public UsageProjection {
        if (attemptNo < 1) throw new IllegalArgumentException("attemptNo 必须大于 0");
        if (usageSource == UsageSource.UNKNOWN && (inputTokens != null || outputTokens != null)) {
            throw new IllegalArgumentException("UNKNOWN 用量不能填写 token 总数");
        }
        if (inputTokens != null && inputTokens < 0 || outputTokens != null && outputTokens < 0) {
            throw new IllegalArgumentException("token 不能为负数");
        }
    }
}
