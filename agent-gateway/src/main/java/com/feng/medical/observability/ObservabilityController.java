package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import com.feng.medical.conversation.RunStatus;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class ObservabilityController {
    private final ObservabilityService observability;
    public ObservabilityController(ObservabilityService observability) { this.observability = observability; }
    @GetMapping("/runs") public RunPage runs(@RequestParam(required=false) UUID userId, @RequestParam(required=false) RunMode mode,
            @RequestParam(required=false) RunStatus status, @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant createdTo, @RequestParam(required=false) Integer limit,
            @RequestParam(required=false) String cursor) { return observability.runs(new RunSearchFilter(userId,mode,status,createdFrom,createdTo,limit==null?30:limit,cursor)); }
    @GetMapping("/runs/{runId}/trace") public TraceView trace(@PathVariable UUID runId) { return observability.trace(runId); }
    @GetMapping("/usage") public UsageSummary usage(@RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant dateTo,@RequestParam(required=false) UUID userId,
            @RequestParam(required=false) String model,@RequestParam(required=false) RunMode mode) { return observability.usage(new UsageQuery(dateFrom,dateTo,userId,model,mode)); }
}
