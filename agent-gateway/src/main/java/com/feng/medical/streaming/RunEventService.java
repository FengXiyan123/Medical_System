package com.feng.medical.streaming;

import java.util.List;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RunEventService {
    private final RunEventJournal journal;
    private final RunEventBroadcaster broadcaster;
    private final RunEventFinalizer finalizer;

    public RunEventService(RunEventJournal journal, RunEventBroadcaster broadcaster, RunEventFinalizer finalizer) {
        this.journal = journal;
        this.broadcaster = broadcaster;
        this.finalizer = finalizer;
    }

    /** Stores before publishing so reconnecting clients can never miss an accepted event. */
    @Transactional
    public boolean accept(RunEvent event) {
        if (!journal.append(event)) return false;
        if (event.type() == RunEventType.RUN_STARTED || event.type() == RunEventType.RETRIEVAL_COMPLETED || event.type().isTerminal()) finalizer.finalizeRun(event);
        broadcaster.publish(event);
        return true;
    }

    public List<RunEvent> replay(UUID runId, long sequenceExclusive) {
        long after = Math.max(0, sequenceExclusive);
        long earliest = journal.earliestSequence(runId);
        if (after > 0 && earliest > after + 1) {
            throw new EventHistoryExpiredException();
        }
        return journal.after(runId, after, 500);
    }

    @Transactional
    public boolean cancel(UUID runId) {
        return accept(new RunEvent(UUID.randomUUID(), runId, journal.latestSequence(runId) + 1,
                RunEventType.RUN_CANCELLED, "{}", Instant.now()));
    }
}
