package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UsageAggregate(LocalDate date, UUID userId, String model, RunMode mode, String purpose,
                             long knownInputTokens, long knownOutputTokens, long unknownCallCount,
                             BigDecimal estimatedCost, String currency) { }
