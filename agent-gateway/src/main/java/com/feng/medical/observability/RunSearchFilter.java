package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import com.feng.medical.conversation.RunStatus;
import java.time.Instant;
import java.util.UUID;

public record RunSearchFilter(UUID userId, RunMode mode, RunStatus status, Instant createdFrom,
                              Instant createdTo, int limit, String cursor) {
    public RunSearchFilter {
        if (limit < 1 || limit > 100) limit = 30;
    }
}
