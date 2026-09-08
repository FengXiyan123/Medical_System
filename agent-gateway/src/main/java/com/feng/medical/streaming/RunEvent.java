package com.feng.medical.streaming;

import java.time.Instant;
import java.util.UUID;

public record RunEvent(UUID eventId, UUID runId, long sequence, RunEventType type,
                       String payload, Instant createdAt) {
    public RunEvent {
        if (sequence < 1) throw new IllegalArgumentException("事件序号必须从 1 开始");
        if (payload == null || payload.isBlank()) throw new IllegalArgumentException("事件内容不能为空");
    }
}
