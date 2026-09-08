package com.feng.medical.file;

import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface FileStorage {
    StoredFile store(UUID knowledgeBaseId, String extension, MultipartFile file);
    void delete(String storageKey);
}
