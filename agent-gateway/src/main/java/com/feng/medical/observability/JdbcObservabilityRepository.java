package com.feng.medical.observability;

import com.feng.medical.conversation.RunMode;
import com.feng.medical.conversation.RunStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcObservabilityRepository implements ObservabilityRepository {
    private final JdbcClient jdbc;
    JdbcObservabilityRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public void upsertUsage(UsageProjection value) {
        jdbc.sql("INSERT INTO usage_projection (invocation_id, attempt_no, run_id, user_id, mode, purpose, provider, model, input_tokens, output_tokens, usage_source, estimated_cost, currency, price_snapshot, observed_at, synced_at) "
                        + "VALUES (:invocationId,:attemptNo,:runId,:userId,:mode,:purpose,:provider,:model,:inputTokens,:outputTokens,:usageSource,:estimatedCost,:currency,CAST(:priceSnapshot AS JSON),:observedAt,UTC_TIMESTAMP(3)) "
                        + "ON DUPLICATE KEY UPDATE input_tokens=VALUES(input_tokens), output_tokens=VALUES(output_tokens), usage_source=VALUES(usage_source), estimated_cost=VALUES(estimated_cost), currency=VALUES(currency), price_snapshot=VALUES(price_snapshot), observed_at=VALUES(observed_at), synced_at=UTC_TIMESTAMP(3)")
                .param("invocationId", value.invocationId().toString()).param("attemptNo", value.attemptNo())
                .param("runId", value.runId().toString()).param("userId", value.userId().toString()).param("mode", value.mode().name())
                .param("purpose", value.purpose()).param("provider", value.provider()).param("model", value.model())
                .param("inputTokens", value.inputTokens()).param("outputTokens", value.outputTokens()).param("usageSource", value.usageSource().name())
                .param("estimatedCost", value.estimatedCost()).param("currency", value.currency()).param("priceSnapshot", value.priceSnapshot() == null ? "{}" : value.priceSnapshot())
                .param("observedAt", Timestamp.from(value.observedAt())).update();
    }

    @Override public List<UsageProjection> usages(UsageQuery query) {
        StringBuilder sql = new StringBuilder("SELECT invocation_id,attempt_no,run_id,user_id,mode,purpose,provider,model,input_tokens,output_tokens,usage_source,estimated_cost,currency,price_snapshot,observed_at FROM usage_projection WHERE 1=1");
        var spec = jdbc.sql(sql.toString());
        // Dynamic query construction is intentionally kept bounded to whitelisted filter fields.
        if (query.dateFrom() != null) { sql.append(" AND observed_at >= :dateFrom"); }
        if (query.dateTo() != null) { sql.append(" AND observed_at < :dateTo"); }
        if (query.userId() != null) { sql.append(" AND user_id = :userId"); }
        if (query.model() != null && !query.model().isBlank()) { sql.append(" AND model = :model"); }
        if (query.mode() != null) { sql.append(" AND mode = :mode"); }
        var bound = jdbc.sql(sql.toString());
        if (query.dateFrom() != null) bound.param("dateFrom", Timestamp.from(query.dateFrom()));
        if (query.dateTo() != null) bound.param("dateTo", Timestamp.from(query.dateTo()));
        if (query.userId() != null) bound.param("userId", query.userId().toString());
        if (query.model() != null && !query.model().isBlank()) bound.param("model", query.model());
        if (query.mode() != null) bound.param("mode", query.mode().name());
        return bound.query(this::mapUsage).list();
    }

    @Override public RunPage findRuns(RunSearchFilter filter) {
        StringBuilder sql = new StringBuilder("SELECT id,trace_id,user_id,conversation_id,mode,status,route_name,finish_reason,created_at,completed_at FROM agent_run WHERE 1=1");
        if (filter.userId() != null) sql.append(" AND user_id=:userId");
        if (filter.mode() != null) sql.append(" AND mode=:mode");
        if (filter.status() != null) sql.append(" AND status=:status");
        if (filter.createdFrom() != null) sql.append(" AND created_at>=:createdFrom");
        if (filter.createdTo() != null) sql.append(" AND created_at<:createdTo");
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit");
        var bound = jdbc.sql(sql.toString());
        if (filter.userId() != null) bound.param("userId", filter.userId().toString());
        if (filter.mode() != null) bound.param("mode", filter.mode().name());
        if (filter.status() != null) bound.param("status", filter.status().name());
        if (filter.createdFrom() != null) bound.param("createdFrom", Timestamp.from(filter.createdFrom()));
        if (filter.createdTo() != null) bound.param("createdTo", Timestamp.from(filter.createdTo()));
        List<AdminRun> items = bound.param("limit", filter.limit() + 1).query(this::mapRun).list();
        String next = items.size() > filter.limit() ? items.removeLast().createdAt().toString() : null;
        return new RunPage(items, next);
    }

    @Override public TraceView trace(UUID runId) {
        AdminRun run = jdbc.sql("SELECT id,trace_id,user_id,conversation_id,mode,status,route_name,finish_reason,created_at,completed_at FROM agent_run WHERE id=:id")
                .param("id", runId.toString()).query(this::mapRun).optional().orElse(null);
        if (run == null) return null;
        List<TraceSpan> spans = jdbc.sql("SELECT id,parent_span_id,kind,node_name,attempt_no,started_at,finished_at,status,error_code,input_summary,output_summary FROM run_trace_span WHERE run_id=:runId ORDER BY started_at,id")
                .param("runId", runId.toString()).query(this::mapSpan).list();
        List<KnowledgeTraceStage> stages = jdbc.sql("SELECT chunk_id,stage,score,selection_reason,knowledge_base_id,document_id,generation_id,chunk_snapshot FROM run_knowledge_trace WHERE run_id=:runId ORDER BY created_at,id")
                .param("runId", runId.toString()).query(this::mapKnowledge).list();
        List<InvocationAttempt> invocations = jdbc.sql("SELECT invocation_id,attempt_no,purpose,provider,model,input_tokens,output_tokens,usage_source,latency_ms,status,error_code,created_at FROM model_invocation_trace WHERE run_id=:runId ORDER BY created_at,id")
                .param("runId", runId.toString()).query(this::mapInvocation).list();
        List<String> path = spans.stream().map(TraceSpan::nodeName).distinct().toList();
        String error = spans.stream().map(TraceSpan::errorCode).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new TraceView(run, run.traceId(), path, spans, stages, invocations, error);
    }
    private UsageProjection mapUsage(ResultSet rs, int row) throws SQLException { return new UsageProjection(uuid(rs,"invocation_id"),rs.getInt("attempt_no"),uuid(rs,"run_id"),uuid(rs,"user_id"),RunMode.valueOf(rs.getString("mode")),rs.getString("purpose"),rs.getString("provider"),rs.getString("model"),(Long)rs.getObject("input_tokens"),(Long)rs.getObject("output_tokens"),UsageSource.valueOf(rs.getString("usage_source")),rs.getBigDecimal("estimated_cost"),rs.getString("currency"),rs.getString("price_snapshot"),rs.getTimestamp("observed_at").toInstant()); }
    private AdminRun mapRun(ResultSet rs, int row) throws SQLException { Timestamp completed=rs.getTimestamp("completed_at"); return new AdminRun(uuid(rs,"id"),uuid(rs,"trace_id"),uuid(rs,"user_id"),uuid(rs,"conversation_id"),RunMode.valueOf(rs.getString("mode")),RunStatus.valueOf(rs.getString("status")),rs.getString("route_name"),rs.getString("finish_reason"),rs.getTimestamp("created_at").toInstant(),completed==null?null:completed.toInstant()); }
    private TraceSpan mapSpan(ResultSet rs,int row)throws SQLException { Timestamp done=rs.getTimestamp("finished_at"); String parent=rs.getString("parent_span_id"); return new TraceSpan(uuid(rs,"id"),parent==null?null:UUID.fromString(parent),rs.getString("kind"),rs.getString("node_name"),rs.getInt("attempt_no"),rs.getTimestamp("started_at").toInstant(),done==null?null:done.toInstant(),rs.getString("status"),rs.getString("error_code"),rs.getString("input_summary"),rs.getString("output_summary")); }
    private KnowledgeTraceStage mapKnowledge(ResultSet rs,int row)throws SQLException { return new KnowledgeTraceStage(uuid(rs,"chunk_id"),rs.getString("stage"),(Double)rs.getObject("score"),rs.getString("selection_reason"),nullableUuid(rs,"knowledge_base_id"),nullableUuid(rs,"document_id"),nullableUuid(rs,"generation_id"),rs.getString("chunk_snapshot")); }
    private InvocationAttempt mapInvocation(ResultSet rs,int row)throws SQLException { return new InvocationAttempt(uuid(rs,"invocation_id"),rs.getInt("attempt_no"),rs.getString("purpose"),rs.getString("provider"),rs.getString("model"),(Long)rs.getObject("input_tokens"),(Long)rs.getObject("output_tokens"),UsageSource.valueOf(rs.getString("usage_source")),(Long)rs.getObject("latency_ms"),rs.getString("status"),rs.getString("error_code"),rs.getTimestamp("created_at").toInstant()); }
    private UUID uuid(ResultSet rs,String name)throws SQLException{return UUID.fromString(rs.getString(name));}
    private UUID nullableUuid(ResultSet rs,String name)throws SQLException {String value=rs.getString(name);return value==null?null:UUID.fromString(value);}
}
