package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import java.time.Instant;
import java.util.UUID;

public record UsageQuery(Instant dateFrom, Instant dateTo, UUID userId, String model, RunMode mode) { }
