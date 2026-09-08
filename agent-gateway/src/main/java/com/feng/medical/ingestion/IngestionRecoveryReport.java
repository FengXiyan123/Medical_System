package com.feng.medical.ingestion;

/** Counts only durable state transitions performed during one reconciliation pass. */
public record IngestionRecoveryReport(int requeued, int failed, int republished) { }
