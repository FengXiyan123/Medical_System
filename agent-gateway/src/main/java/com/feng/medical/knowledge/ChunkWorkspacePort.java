package com.feng.medical.knowledge;

import java.util.UUID;

public interface ChunkWorkspacePort extends GenerationBuildPort {
    AdminChunkPage list(UUID generationId, String query, Boolean enabled, int offset, int limit);
    AdminChunk get(UUID generationId, UUID chunkId);
    GenerationWorkspace update(UUID generationId, UUID chunkId, String content, Boolean enabled, long expectedEditRevision);
    GenerationWorkspace split(UUID generationId, UUID chunkId, int atCharacter, long expectedEditRevision);
    GenerationWorkspace merge(UUID generationId, UUID firstChunkId, UUID secondChunkId, long expectedEditRevision);
    GenerationBuildSummary index(UUID generationId);
}
