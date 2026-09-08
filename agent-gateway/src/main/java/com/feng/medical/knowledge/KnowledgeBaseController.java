package com.feng.medical.knowledge;

import com.feng.medical.security.AuthenticatedUser;
import com.feng.medical.audit.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/knowledge-bases")
public class KnowledgeBaseController {
    private final KnowledgeBaseService knowledgeBases;
    private final AuditService audit;
    public KnowledgeBaseController(KnowledgeBaseService knowledgeBases, AuditService audit) { this.knowledgeBases = knowledgeBases; this.audit = audit; }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase create(@AuthenticationPrincipal AuthenticatedUser administrator, @Valid @RequestBody KnowledgeBaseRequest request) {
        KnowledgeBase created = knowledgeBases.create(userId(administrator), request.command());
        audit.record(administrator.id(), "KNOWLEDGE_BASE_CREATED", "KNOWLEDGE_BASE", created.id(), null, "{\"accessScope\":\"" + request.accessScope() + "\"}");
        return created;
    }
    @GetMapping public List<KnowledgeBase> list() { return knowledgeBases.listAll(); }
    @GetMapping("/{knowledgeBaseId}") public KnowledgeBase get(@PathVariable UUID knowledgeBaseId) { return knowledgeBases.get(knowledgeBaseId); }
    @PatchMapping("/{knowledgeBaseId}")
    public KnowledgeBase update(@AuthenticationPrincipal AuthenticatedUser administrator, @PathVariable UUID knowledgeBaseId,
                                @Valid @RequestBody KnowledgeBaseRequest request) {
        KnowledgeBase updated = knowledgeBases.update(knowledgeBaseId, userId(administrator), request.command());
        audit.record(administrator.id(), "KNOWLEDGE_AUTHORIZATION_CHANGED", "KNOWLEDGE_BASE", knowledgeBaseId, null, "{\"accessScope\":\"" + request.accessScope() + "\"}");
        return updated;
    }
    @PostMapping("/{knowledgeBaseId}/status")
    public KnowledgeBase status(@PathVariable UUID knowledgeBaseId, @Valid @RequestBody StatusRequest request) {
        return knowledgeBases.changeStatus(knowledgeBaseId, request.status());
    }
    private static UUID userId(AuthenticatedUser user) { if (user == null) throw new AccessDeniedException("需要登录"); return user.id(); }

    public record KnowledgeBaseRequest(@NotBlank @Size(max = 128) String name, @Size(max = 500) String description,
                                       @NotNull KnowledgeAccessScope accessScope, List<UUID> assignedUserIds) {
        CreateKnowledgeBaseCommand command() { return new CreateKnowledgeBaseCommand(name, description, accessScope, assignedUserIds); }
    }
    public record StatusRequest(@NotNull KnowledgeBaseStatus status) { }
}
