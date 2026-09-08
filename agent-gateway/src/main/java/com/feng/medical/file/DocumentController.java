package com.feng.medical.file;

import com.feng.medical.security.AuthenticatedUser;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/admin/knowledge-bases/{knowledgeBaseId}/documents")
public class DocumentController {
    private final DocumentUploadService documents;
    public DocumentController(DocumentUploadService documents) { this.documents = documents; }
    @PostMapping(consumes = "multipart/form-data") @ResponseStatus(HttpStatus.ACCEPTED)
    public UploadedDocument upload(@AuthenticationPrincipal AuthenticatedUser administrator, @PathVariable UUID knowledgeBaseId,
                                   @RequestPart("file") MultipartFile file) {
        if (administrator == null) throw new AccessDeniedException("需要登录");
        return documents.upload(administrator.id(), knowledgeBaseId, file);
    }

    @GetMapping
    public java.util.List<UploadedDocument> list(@PathVariable UUID knowledgeBaseId) {
        return documents.list(knowledgeBaseId);
    }
}
