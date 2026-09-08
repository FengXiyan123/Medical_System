package com.feng.medical.knowledge;

import com.feng.medical.conversation.RunMode;
import com.feng.medical.security.AuthenticatedUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/retrieval-tests")
public class RetrievalTestController {
    private final RetrievalTestService retrievalTests;

    public RetrievalTestController(RetrievalTestService retrievalTests) { this.retrievalTests = retrievalTests; }

    @PostMapping
    public RetrievalTestResult execute(@AuthenticationPrincipal AuthenticatedUser user,
                                       @Valid @RequestBody RetrievalTestRequest request) {
        if (user == null) throw new AccessDeniedException("需要登录");
        return retrievalTests.execute(user.id(), request.query(), request.mode(), request.knowledgeBaseIds(), request.contextTokenLimit());
    }

    public record RetrievalTestRequest(@NotBlank @Size(max = 10_000) String query, RunMode mode,
                                       @Size(max = 5) List<UUID> knowledgeBaseIds, Integer contextTokenLimit) {
        public RetrievalTestRequest {
            mode = mode == null ? RunMode.MANUAL_KB : mode;
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : List.copyOf(knowledgeBaseIds);
        }
    }
}
