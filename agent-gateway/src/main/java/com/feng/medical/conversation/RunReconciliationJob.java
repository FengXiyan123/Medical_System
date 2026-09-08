package com.feng.medical.conversation;

import org.springframework.stereotype.Service;

/** Manual recovery hook for a confirmed internal queue/dispatcher loss. */
@Service
public class RunReconciliationJob {
    private final RunReconciliationPort port;
    public RunReconciliationJob(RunReconciliationPort port) { this.port = port; }
    public int restoreUndeliveredRuns() { return port.resetDispatchForUnfinishedRuns(); }
}
