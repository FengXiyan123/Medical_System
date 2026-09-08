package com.feng.medical.streaming;

import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRunEventRetentionPort implements RunEventRetentionPort {
    private final JdbcClient jdbc;
    JdbcRunEventRetentionPort(JdbcClient jdbc) { this.jdbc = jdbc; }
    @Override public int deleteBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM agent_run_event WHERE created_at < :cutoff")
                .param("cutoff", cutoff).update();
    }
}
