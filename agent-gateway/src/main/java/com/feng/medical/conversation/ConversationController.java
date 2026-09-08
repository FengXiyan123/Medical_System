package com.feng.medical.conversation;

import com.feng.medical.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequestMapping("/api/v1/conversations")
public class ConversationController {
    private final ConversationService conversations;

    public ConversationController(ConversationService conversations) {
        this.conversations = conversations;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Conversation create(@AuthenticationPrincipal AuthenticatedUser user, @Valid @RequestBody CreateConversationRequest request) {
        return conversations.createConversation(userId(user), request.title());
    }

    @GetMapping
    public ConversationPage list(@AuthenticationPrincipal AuthenticatedUser user,
                                 @RequestParam(required = false) String cursor,
                                 @RequestParam(required = false) Integer limit) {
        return conversations.listConversations(userId(user), cursor, limit);
    }

    @GetMapping("/{conversationId}")
    public Conversation get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId) {
        return conversations.getConversation(userId(user), conversationId);
    }

    @PatchMapping("/{conversationId}")
    public Conversation update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId,
                               @Valid @RequestBody UpdateConversationRequest request) {
        return conversations.updateConversation(userId(user), conversationId, request.title(), request.status());
    }

    @DeleteMapping("/{conversationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId) {
        conversations.deleteConversation(userId(user), conversationId);
    }

    @GetMapping("/{conversationId}/messages")
    public MessagePage messages(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) Integer limit) {
        return conversations.listMessages(userId(user), conversationId, cursor, limit);
    }

    @PostMapping("/{conversationId}/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAcceptance submitRun(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId,
                                   @RequestHeader("Idempotency-Key") String idempotencyKey,
                                   @Valid @RequestBody CreateRunRequest request) {
        return conversations.submitRun(userId(user), conversationId,
                new SubmitRunCommand(request.question(), request.mode(), request.selectedKnowledgeBaseIds(), idempotencyKey));
    }

    @PostMapping("/{conversationId}/runs/{runId}/regenerate")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunAcceptance regenerate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID conversationId,
                                    @PathVariable UUID runId, @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return conversations.regenerate(userId(user), conversationId, runId, idempotencyKey);
    }

    private UUID userId(AuthenticatedUser user) {
        if (user == null) {
            throw new AccessDeniedException("需要登录");
        }
        return user.id();
    }

    public record CreateConversationRequest(@NotBlank @Size(max = 255) String title) {
    }

    public record UpdateConversationRequest(@Size(min = 1, max = 255) String title, ConversationStatus status) {
    }

    public record CreateRunRequest(@NotBlank @Size(max = 10_000) String question, @NotNull RunMode mode,
                                   @Size(max = 5) List<@NotBlank String> selectedKnowledgeBaseIds) {
    }
}
