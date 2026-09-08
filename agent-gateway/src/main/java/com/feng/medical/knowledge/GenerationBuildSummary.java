package com.feng.medical.knowledge;

import java.util.UUID;

/** A verified immutable build report returned by the Python/pgvector side. */
public record GenerationBuildSummary(
        UUID generationId,
        UUID documentId,
        GenerationBuildStatus buildStatus,
        String manifestHash,
        int chunkCount,
        String embeddingProfileId,
        int embeddingDimension
) {
    public GenerationBuildSummary {
        if (generationId == null || documentId == null || buildStatus == null || manifestHash == null || manifestHash.isBlank()
                || chunkCount < 0 || embeddingProfileId == null || embeddingProfileId.isBlank() || embeddingDimension < 1) {
            throw new IllegalArgumentException("generation build summary is invalid");
        }
    }
}
