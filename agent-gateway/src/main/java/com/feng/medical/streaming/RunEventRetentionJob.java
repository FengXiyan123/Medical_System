package com.feng.medical.streaming;

import java.time.Clock;
import java.time.Duration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Retains replay events for seven days; clients with an older cursor receive 410 and use a snapshot. */
@Service
public class RunEventRetentionJob {
    private final RunEventRetentionPort port;
    private final Clock clock;
    private final int retentionDays;

    @Autowired
    public RunEventRetentionJob(RunEventRetentionPort port) { this(port, Clock.systemUTC(), 7); }
    RunEventRetentionJob(RunEventRetentionPort port, Clock clock, int retentionDays) {
        if (retentionDays < 1) throw new IllegalArgumentException("事件保留天数必须大于零");
        this.port = port; this.clock = clock; this.retentionDays = retentionDays;
    }

    @Scheduled(fixedDelayString = "${app.streaming.retention-delay-ms:3600000}")
    public int pruneExpiredHistory() {
        return port.deleteBefore(clock.instant().minus(Duration.ofDays(retentionDays)));
    }
}
