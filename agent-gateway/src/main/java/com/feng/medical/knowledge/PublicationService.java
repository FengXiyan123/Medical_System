package com.feng.medical.knowledge;

import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicationService {
    private final DocumentPublicationRepository publications;
    private final GenerationBuildPort generationBuilds;

    public PublicationService(DocumentPublicationRepository publications, GenerationBuildPort generationBuilds) {
        this.publications = publications;
        this.generationBuilds = generationBuilds;
    }

    /**
     * Makes a fully-built generation searchable in one MySQL transaction.
     * The generation is checked before the pointer changes, so an indexing failure
     * cannot displace the previous active version.
     */
    @Transactional
    public PublicationResult publish(UUID documentId, UUID generationId, UUID expectedActiveGenerationId) {
        GenerationBuildSummary generation = generationBuilds.getBuild(generationId);
        if (!generation.documentId().equals(documentId)) {
            throw new IllegalArgumentException("generation does not belong to document");
        }
        if (generation.buildStatus() != GenerationBuildStatus.BUILD_READY || generation.chunkCount() < 1) {
            throw new GenerationNotReadyException("generation is not ready for publication");
        }
        UUID actualActiveGenerationId = publications.activeGenerationId(documentId);
        if (!Objects.equals(actualActiveGenerationId, expectedActiveGenerationId)) {
            throw new PublicationConflictException("active generation changed; refresh before publishing");
        }
        if (!publications.switchActiveGeneration(documentId, expectedActiveGenerationId, generationId)) {
            throw new PublicationConflictException("active generation changed; refresh before publishing");
        }
        if (actualActiveGenerationId != null && !actualActiveGenerationId.equals(generationId)) {
            publications.setPublicationStatus(actualActiveGenerationId, PublicationStatus.RETIRED);
        }
        publications.setPublicationStatus(generationId, PublicationStatus.ACTIVE);
        return new PublicationResult(documentId, generationId, generation.manifestHash());
    }
}
