package com.feng.medical.knowledge;

import com.feng.medical.audit.AuditService;
import com.feng.medical.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/api/admin/documents")
public class PublicationController {
    private final PublicationService publications;
    private final AuditService audit;

    public PublicationController(PublicationService publications, AuditService audit) {
        this.publications = publications;
        this.audit = audit;
    }

    @PostMapping("/{documentId}/publish")
    public PublicationResult publish(@AuthenticationPrincipal AuthenticatedUser administrator, @PathVariable UUID documentId, @Valid @RequestBody PublishRequest request) {
        PublicationResult result = publications.publish(documentId, request.generationId(), request.expectedActiveGenerationId());
        audit.record(administrator.id(), "KNOWLEDGE_GENERATION_PUBLISHED", "DOCUMENT", documentId, null,
                "{\"generationId\":\"" + request.generationId() + "\"}");
        return result;
    }

    public record PublishRequest(@NotNull UUID generationId, UUID expectedActiveGenerationId) {
    }
}
