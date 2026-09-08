package com.feng.medical.observability;

import java.util.List;
import java.util.UUID;
public record TraceView(AdminRun run, UUID traceId, List<String> path, List<TraceSpan> spans,
                        List<KnowledgeTraceStage> knowledgeStages, List<InvocationAttempt> invocations,
                        String error) { }
