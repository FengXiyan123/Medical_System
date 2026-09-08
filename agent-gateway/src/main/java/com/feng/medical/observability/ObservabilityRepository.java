package com.feng.medical.observability;

import java.util.List;
import java.util.UUID;
public interface ObservabilityRepository {
    void upsertUsage(UsageProjection value);
    List<UsageProjection> usages(UsageQuery query);
    RunPage findRuns(RunSearchFilter filter);
    TraceView trace(UUID runId);
}
