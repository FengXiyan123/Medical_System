package com.feng.medical.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.feng.medical.conversation.RunMode;
import com.feng.medical.streaming.ServiceCallbackTokenVerifier;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Private gateway boundary for Agent Core retrieval and knowledge_read calls. */
@RestController
@RequestMapping("/api/internal/v1/knowledge")
public class KnowledgeScopeController {
    private final KnowledgeScopeService scopes;
    private final ServiceCallbackTokenVerifier callbacks;

    public KnowledgeScopeController(KnowledgeScopeService scopes, ServiceCallbackTokenVerifier callbacks) {
        this.scopes = scopes;
        this.callbacks = callbacks;
    }

    @PostMapping("/scopes:resolve")
    @ResponseStatus(HttpStatus.OK)
    public ResolvedKnowledgeScope resolve(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
                                          @Valid @RequestBody ScopeRequestBody request) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new AccessDeniedException("缺少服务令牌");
        }
        callbacks.verifyKnowledgeScope(authorization.substring("Bearer ".length()), request.runId().toString());
        return scopes.resolve(new KnowledgeScopeRequest(request.runId(), request.userId(), request.mode(),
                request.requestedKnowledgeBaseIds() == null ? List.of() : request.requestedKnowledgeBaseIds()));
    }

    public record ScopeRequestBody(@JsonProperty("run_id") @NotNull UUID runId,
                                   @JsonProperty("user_id") @NotNull UUID userId, @NotNull RunMode mode,
                                   @JsonProperty("requested_knowledge_base_ids") List<UUID> requestedKnowledgeBaseIds) { }
}
