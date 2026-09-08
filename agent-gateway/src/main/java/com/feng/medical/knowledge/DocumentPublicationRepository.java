package com.feng.medical.knowledge;

import java.util.UUID;

/** MySQL side of an active-generation pointer switch. */
public interface DocumentPublicationRepository {
    UUID activeGenerationId(UUID documentId);

    /** Compare-and-set the document pointer. Returns false on an optimistic-lock conflict. */
    boolean switchActiveGeneration(UUID documentId, UUID expectedActiveGenerationId, UUID nextGenerationId);

    void setPublicationStatus(UUID generationId, PublicationStatus status);
}
