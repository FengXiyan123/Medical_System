package com.feng.medical.streaming;

import java.util.List;
import java.util.UUID;

public interface RunEventJournal {
    /** @return true exactly once for each producer event id. */
    boolean append(RunEvent event);
    List<RunEvent> after(UUID runId, long sequenceExclusive, int limit);
    long latestSequence(UUID runId);
    /** Returns zero if there are no retained events for a run. */
    default long earliestSequence(UUID runId) { return 0; }
}
