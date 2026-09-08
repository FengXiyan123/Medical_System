package com.feng.medical.ingestion;

public interface IngestionEventPublisher { String publish(OutboxEvent event); }
