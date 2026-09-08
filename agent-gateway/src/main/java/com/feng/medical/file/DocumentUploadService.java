package com.feng.medical.file;

import com.feng.medical.ingestion.IngestionEventOutbox;
import com.feng.medical.ingestion.IngestionTask;
import com.feng.medical.ingestion.IngestionTaskRepository;
import com.feng.medical.ingestion.IngestionTaskStage;
import com.feng.medical.ingestion.IngestionTaskStatus;
import com.feng.medical.knowledge.KnowledgeBase;
import com.feng.medical.knowledge.KnowledgeBaseRepository;
import com.feng.medical.knowledge.KnowledgeBaseStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentUploadService {
    private final KnowledgeBaseRepository knowledgeBases; private final KnowledgeDocumentRepository documents;
    private final IngestionTaskRepository tasks; private final IngestionEventOutbox outbox; private final FileStorage storage; private final Clock clock;
    public DocumentUploadService(KnowledgeBaseRepository knowledgeBases, KnowledgeDocumentRepository documents, IngestionTaskRepository tasks,
                                 IngestionEventOutbox outbox, FileStorage storage) { this(knowledgeBases, documents, tasks, outbox, storage, Clock.systemUTC()); }
    @Autowired
    DocumentUploadService(KnowledgeBaseRepository knowledgeBases, KnowledgeDocumentRepository documents, IngestionTaskRepository tasks,
                          IngestionEventOutbox outbox, FileStorage storage, Clock clock) {
        this.knowledgeBases = knowledgeBases; this.documents = documents; this.tasks = tasks; this.outbox = outbox; this.storage = storage; this.clock = clock;
    }
    @Transactional
    public UploadedDocument upload(UUID administratorId, UUID knowledgeBaseId, MultipartFile file) {
        KnowledgeBase base = knowledgeBases.findById(knowledgeBaseId);
        if (base == null) throw new InvalidUploadException("知识库不存在");
        if (base.status() == KnowledgeBaseStatus.ARCHIVED) throw new InvalidUploadException("已归档知识库不能上传文件");
        AllowedUploadType type = AllowedUploadType.require(file == null ? null : file.getOriginalFilename(), file == null ? null : file.getContentType());
        String filename = displayFilename(file.getOriginalFilename());
        StoredFile stored = storage.store(knowledgeBaseId, type.extension(), file);
        if (documents.findByKnowledgeBaseAndHash(knowledgeBaseId, stored.contentSha256()) != null) {
            storage.delete(stored.storageKey()); throw new InvalidUploadException("同一知识库中已上传相同文件");
        }
        Instant now = clock.instant();
        UploadedDocument document = new UploadedDocument(UUID.randomUUID(), knowledgeBaseId, filename, file.getContentType(), stored.storageKey(),
                stored.contentSha256(), DocumentStatus.UPLOADED, 0, administratorId, now, now);
        try {
            documents.create(document);
            IngestionTask task = tasks.create(IngestionTask.queued(document.id(), now));
            outbox.enqueue(task, document);
            return document;
        } catch (RuntimeException exception) {
            storage.delete(stored.storageKey()); throw exception;
        }
    }
    @Transactional(readOnly = true)
    public java.util.List<UploadedDocument> list(UUID knowledgeBaseId) {
        return documents.listByKnowledgeBase(knowledgeBaseId);
    }
    private static String displayFilename(String input) {
        if (input == null || input.isBlank()) throw new InvalidUploadException("文件名不能为空");
        String value = input.replace('\\', '/'); value = value.substring(value.lastIndexOf('/') + 1).trim();
        if (value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl)) throw new InvalidUploadException("文件名无效");
        return value;
    }
}
