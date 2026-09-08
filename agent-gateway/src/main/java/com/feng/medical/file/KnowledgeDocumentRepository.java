package com.feng.medical.file;

import java.util.UUID;
import java.util.List;

public interface KnowledgeDocumentRepository {
    UploadedDocument create(UploadedDocument value);
    UploadedDocument findByKnowledgeBaseAndHash(UUID knowledgeBaseId, String hash);
    List<UploadedDocument> listByKnowledgeBase(UUID knowledgeBaseId);
}
