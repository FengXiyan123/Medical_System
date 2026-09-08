package com.feng.medical.knowledge;

import java.util.UUID;

public record PublicationResult(UUID documentId, UUID activeGenerationId, String manifestHash) {
}
