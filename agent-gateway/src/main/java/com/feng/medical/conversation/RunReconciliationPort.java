package com.feng.medical.conversation;

/** Restores a lost internal-delivery marker without creating another user run. */
public interface RunReconciliationPort {
    int resetDispatchForUnfinishedRuns();
}
