package com.feng.medical.knowledge;

import com.feng.medical.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeDirectoryController {
    private final KnowledgeBaseService knowledgeBases;
    public KnowledgeDirectoryController(KnowledgeBaseService knowledgeBases) { this.knowledgeBases = knowledgeBases; }
    @GetMapping public List<KnowledgeBase> listAccessible(@AuthenticationPrincipal AuthenticatedUser user) {
        if (user == null) throw new AccessDeniedException("需要登录");
        return knowledgeBases.listAccessible(user.id());
    }
}
