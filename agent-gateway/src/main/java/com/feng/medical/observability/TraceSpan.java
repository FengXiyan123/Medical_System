package com.feng.medical.observability;

import java.time.Instant;
import java.util.UUID;
public record TraceSpan(UUID id, UUID parentSpanId, String kind, String nodeName, int attemptNo,
                        Instant startedAt, Instant finishedAt, String status, String errorCode,
                        String inputSummary, String outputSummary) { }
