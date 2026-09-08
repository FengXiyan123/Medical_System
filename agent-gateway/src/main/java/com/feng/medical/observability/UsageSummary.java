package com.feng.medical.observability;

import java.time.Instant;
import java.util.List;

public record UsageSummary(List<UsageAggregate> items, Instant syncedAt) { }
