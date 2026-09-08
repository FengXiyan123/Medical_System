package com.feng.medical.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
@Repository
class JdbcAuditEventRepository implements AuditEventRepository {
    private final JdbcClient jdbc;
    JdbcAuditEventRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public void append(AuditEvent event) { jdbc.sql("INSERT INTO audit_event (id,actor_id,action,target_type,target_id,trace_id,redacted_diff,created_at) VALUES (:id,:actorId,:action,:targetType,:targetId,:traceId,CAST(:diff AS JSON),:createdAt)")
            .param("id",event.id().toString()).param("actorId",event.actorId()==null?null:event.actorId().toString()).param("action",event.action()).param("targetType",event.targetType()).param("targetId",event.targetId()==null?null:event.targetId().toString()).param("traceId",event.traceId()==null?null:event.traceId().toString()).param("diff",event.redactedDiff()).param("createdAt",Timestamp.from(event.createdAt())).update(); }
    @Override public List<AuditEvent> find(UUID actorId,String action,Instant from,Instant to,int limit) { StringBuilder sql=new StringBuilder("SELECT id,actor_id,action,target_type,target_id,trace_id,redacted_diff,created_at FROM audit_event WHERE 1=1"); if(actorId!=null)sql.append(" AND actor_id=:actorId");if(action!=null&&!action.isBlank())sql.append(" AND action=:action");if(from!=null)sql.append(" AND created_at>=:from");if(to!=null)sql.append(" AND created_at<:to");sql.append(" ORDER BY created_at DESC LIMIT :limit");var q=jdbc.sql(sql.toString());if(actorId!=null)q.param("actorId",actorId.toString());if(action!=null&&!action.isBlank())q.param("action",action);if(from!=null)q.param("from",Timestamp.from(from));if(to!=null)q.param("to",Timestamp.from(to));return q.param("limit",Math.min(Math.max(limit,1),100)).query(this::map).list(); }
    private AuditEvent map(ResultSet rs,int row)throws SQLException {String actor=rs.getString("actor_id"),target=rs.getString("target_id"),trace=rs.getString("trace_id");return new AuditEvent(UUID.fromString(rs.getString("id")),actor==null?null:UUID.fromString(actor),rs.getString("action"),rs.getString("target_type"),target==null?null:UUID.fromString(target),trace==null?null:UUID.fromString(trace),rs.getString("redacted_diff"),rs.getTimestamp("created_at").toInstant());}
}
