package com.feng.medical.audit;

import com.feng.medical.conversation.ChatMessage;
import com.feng.medical.conversation.MessageRole;
import com.feng.medical.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The only administrator endpoint that returns conversation body; every view is audited. */
@RestController
@RequestMapping("/api/admin/conversations")
public class AdminConversationController {
    private final JdbcClient jdbc;
    private final AuditService audit;
    public AdminConversationController(JdbcClient jdbc, AuditService audit) { this.jdbc = jdbc; this.audit = audit; }
    @GetMapping("/{conversationId}/messages")
    public List<ChatMessage> messages(@AuthenticationPrincipal AuthenticatedUser administrator, @PathVariable UUID conversationId) {
        audit.record(administrator.id(), "CONVERSATION_BODY_VIEWED", "CONVERSATION", conversationId, null,
                "{\"reason\":\"administrator review\"}");
        return jdbc.sql("SELECT id,conversation_id,run_id,role,content,citation_manifest,created_at FROM chat_message WHERE conversation_id=:conversationId ORDER BY created_at,id")
                .param("conversationId", conversationId.toString()).query((rs, row) -> new ChatMessage(UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("conversation_id")), rs.getString("run_id") == null ? null : UUID.fromString(rs.getString("run_id")),
                        MessageRole.valueOf(rs.getString("role")), rs.getString("content"), rs.getTimestamp("created_at").toInstant(), rs.getString("citation_manifest"))).list();
    }
}
