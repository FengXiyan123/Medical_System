package com.feng.medical.knowledge;

import java.util.UUID;

/** Reads a generation build proof from the service that owns pgvector data. */
@FunctionalInterface
public interface GenerationBuildPort {
    GenerationBuildSummary getBuild(UUID generationId);
}
