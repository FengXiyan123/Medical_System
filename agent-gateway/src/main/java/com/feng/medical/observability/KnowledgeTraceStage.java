package com.feng.medical.observability;

import java.util.UUID;
public record KnowledgeTraceStage(UUID chunkId, String stage, Double score, String selectionReason,
                                  UUID knowledgeBaseId, UUID documentId, UUID generationId, String snapshot) { }
