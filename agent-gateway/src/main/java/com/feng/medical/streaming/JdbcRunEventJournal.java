package com.feng.medical.streaming;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunEventJournal implements RunEventJournal {
    private final JdbcClient jdbc;
    JdbcRunEventJournal(JdbcClient jdbc) { this.jdbc = jdbc; }

    @Override public boolean append(RunEvent event) {
        try {
            return jdbc.sql("INSERT INTO agent_run_event (event_id, run_id, sequence_no, event_type, payload, created_at) "
                            + "VALUES (:eventId, :runId, :sequence, :eventType, CAST(:payload AS JSON), :createdAt)")
                    .param("eventId", event.eventId().toString()).param("runId", event.runId().toString())
                    .param("sequence", event.sequence()).param("eventType", event.type().wireName())
                    .param("payload", event.payload()).param("createdAt", Timestamp.from(event.createdAt())).update() == 1;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override public List<RunEvent> after(UUID runId, long sequenceExclusive, int limit) {
        return jdbc.sql("SELECT event_id, run_id, sequence_no, event_type, payload, created_at FROM agent_run_event "
                        + "WHERE run_id = :runId AND sequence_no > :sequence ORDER BY sequence_no ASC LIMIT :limit")
                .param("runId", runId.toString()).param("sequence", sequenceExclusive).param("limit", limit)
                .query(this::map).list();
    }

    @Override public long latestSequence(UUID runId) {
        Long value = jdbc.sql("SELECT COALESCE(MAX(sequence_no), 0) FROM agent_run_event WHERE run_id = :runId")
                .param("runId", runId.toString()).query(Long.class).single();
        return value == null ? 0 : value;
    }

    @Override public long earliestSequence(UUID runId) {
        Long value = jdbc.sql("SELECT COALESCE(MIN(sequence_no), 0) FROM agent_run_event WHERE run_id = :runId")
                .param("runId", runId.toString()).query(Long.class).single();
        return value == null ? 0 : value;
    }

    private RunEvent map(ResultSet rs, int row) throws SQLException {
        return new RunEvent(UUID.fromString(rs.getString("event_id")), UUID.fromString(rs.getString("run_id")),
                rs.getLong("sequence_no"), RunEventType.fromWireName(rs.getString("event_type")),
                rs.getString("payload"), rs.getTimestamp("created_at").toInstant());
    }
}
