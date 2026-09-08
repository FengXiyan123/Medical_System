package com.feng.medical.ingestion;

import com.feng.medical.file.UploadedDocument;

public interface IngestionEventOutbox { void enqueue(IngestionTask task, UploadedDocument document); }
