package com.feng.medical.observability;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ObservabilityService {
    private final ObservabilityRepository repository;
    public ObservabilityService(ObservabilityRepository repository) { this.repository = repository; }
    public void recordUsage(UsageProjection projection) { repository.upsertUsage(projection); }
    public RunPage runs(RunSearchFilter filter) { return repository.findRuns(filter); }
    public TraceView trace(java.util.UUID runId) { return repository.trace(runId); }
    public UsageSummary usage(UsageQuery query) {
        Map<Key, Totals> totals = new LinkedHashMap<>();
        for (UsageProjection usage : repository.usages(query)) {
            Key key = new Key(usage.observedAt().atZone(ZoneOffset.UTC).toLocalDate(), usage.userId(), usage.model(), usage.mode(), usage.purpose(), usage.currency());
            Totals total = totals.computeIfAbsent(key, ignored -> new Totals());
            if (usage.usageSource() == UsageSource.UNKNOWN) total.unknown++;
            else { total.input += usage.inputTokens() == null ? 0 : usage.inputTokens(); total.output += usage.outputTokens() == null ? 0 : usage.outputTokens(); }
            if (usage.estimatedCost() != null) total.cost = total.cost.add(usage.estimatedCost());
        }
        List<UsageAggregate> rows = totals.entrySet().stream().map(entry -> new UsageAggregate(entry.getKey().date, entry.getKey().userId,
                entry.getKey().model, entry.getKey().mode, entry.getKey().purpose, entry.getValue().input, entry.getValue().output,
                entry.getValue().unknown, entry.getValue().cost, entry.getKey().currency)).toList();
        return new UsageSummary(rows, java.time.Instant.now());
    }
    private record Key(java.time.LocalDate date, java.util.UUID userId, String model, com.feng.medical.conversation.RunMode mode, String purpose, String currency) { }
    private static final class Totals { long input; long output; long unknown; BigDecimal cost = BigDecimal.ZERO; }
}
