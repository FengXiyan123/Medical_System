package com.feng.medical.file;

import java.time.Instant;
import java.util.UUID;

public record UploadedDocument(UUID id, UUID knowledgeBaseId, String originalFilename, String mediaType,
                               String storageKey, String contentSha256, DocumentStatus status,
                               int latestVersion, UUID uploadedBy, Instant createdAt, Instant updatedAt) {
}
