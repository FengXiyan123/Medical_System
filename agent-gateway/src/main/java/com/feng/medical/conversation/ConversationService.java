package com.feng.medical.conversation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversations;
    private final RunRepository runs;
    private final ChatMessageRepository messages;
    private final RunDispatchPort dispatch;

    public ConversationService(ConversationRepository conversations, RunRepository runs,
                               ChatMessageRepository messages, RunDispatchPort dispatch) {
        this.conversations = conversations;
        this.runs = runs;
        this.messages = messages;
        this.dispatch = dispatch;
    }

    @Transactional
    public Conversation createConversation(UUID userId, String title) {
        return conversations.create(userId, requiredText(title, "会话标题", 255));
    }

    @Transactional(readOnly = true)
    public Conversation getConversation(UUID userId, UUID conversationId) {
        return requiredConversation(userId, conversationId);
    }

    @Transactional(readOnly = true)
    public ConversationPage listConversations(UUID userId, String encodedCursor, Integer requestedLimit) {
        int limit = pageSize(requestedLimit);
        ConversationCursor cursor = conversationCursor(encodedCursor);
        List<Conversation> candidates = conversations.listOwned(userId, cursor, limit + 1);
        boolean hasMore = candidates.size() > limit;
        List<Conversation> items = hasMore ? candidates.subList(0, limit) : candidates;
        String next = hasMore ? CursorCodec.encode(items.getLast().updatedAt(), items.getLast().id()) : null;
        return new ConversationPage(List.copyOf(items), next);
    }

    @Transactional
    public Conversation updateConversation(UUID userId, UUID conversationId, String title, ConversationStatus status) {
        if (title == null && status == null) {
            throw new IllegalArgumentException("至少提供一个要更新的字段");
        }
        String normalizedTitle = title == null ? null : requiredText(title, "会话标题", 255);
        Conversation updated = conversations.updateOwned(userId, conversationId, normalizedTitle, status);
        if (updated == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return updated;
    }

    @Transactional
    public void archiveConversation(UUID userId, UUID conversationId) {
        updateConversation(userId, conversationId, null, ConversationStatus.ARCHIVED);
    }

    @Transactional
    public void deleteConversation(UUID userId, UUID conversationId) {
        requiredConversation(userId, conversationId);
        if (runs.hasActiveRun(conversationId)) {
            throw new ActiveRunConflictException();
        }
        if (!conversations.deleteOwned(userId, conversationId)) {
            throw new ResourceNotFoundException("会话不存在");
        }
    }

    @Transactional(readOnly = true)
    public MessagePage listMessages(UUID userId, UUID conversationId, String encodedCursor, Integer requestedLimit) {
        requiredConversation(userId, conversationId);
        int limit = pageSize(requestedLimit);
        MessageCursor cursor = messageCursor(encodedCursor);
        List<ChatMessage> candidates = messages.list(conversationId, cursor, limit + 1);
        boolean hasMore = candidates.size() > limit;
        List<ChatMessage> items = hasMore ? candidates.subList(0, limit) : candidates;
        String next = hasMore ? CursorCodec.encode(items.getLast().createdAt(), items.getLast().id()) : null;
        return new MessagePage(List.copyOf(items), next);
    }

    @Transactional
    public RunAcceptance submitRun(UUID userId, UUID conversationId, SubmitRunCommand command) {
        Conversation conversation = requiredConversation(userId, conversationId);
        if (conversation.status() != ConversationStatus.ACTIVE) {
            throw new IllegalStateException("已归档会话不能发起新对话");
        }
        ValidatedCommand validated = validate(command);
        AgentRun existing = runs.findByUserAndIdempotencyKey(userId, validated.idempotencyKey());
        if (existing != null) {
            return sameRequestOrConflict(existing, validated.requestHash());
        }
        if (runs.hasActiveRun(conversationId)) {
            throw new ActiveRunConflictException();
        }

        if (messages.list(conversationId, null, 1).isEmpty()) {
            conversations.updateOwned(userId, conversationId, titleFromFirstQuestion(validated.question()), null);
        }

        AgentRun created = newRun(userId, conversationId, validated, null);
        try {
            runs.create(created);
        } catch (DataIntegrityViolationException exception) {
            AgentRun duplicate = runs.findByUserAndIdempotencyKey(userId, validated.idempotencyKey());
            if (duplicate != null) {
                return sameRequestOrConflict(duplicate, validated.requestHash());
            }
            throw new ActiveRunConflictException();
        }
        messages.create(new ChatMessage(created.requestMessageId(), conversationId, created.id(), MessageRole.USER,
                validated.question(), Instant.now(), null));
        dispatch.enqueue(created, validated.selectedKnowledgeBaseIds());
        return new RunAcceptance(created, false);
    }

    @Transactional
    public RunAcceptance regenerate(UUID userId, UUID conversationId, UUID originalRunId, String idempotencyKey) {
        requiredConversation(userId, conversationId);
        AgentRun original = runs.findOwned(userId, conversationId, originalRunId);
        if (original == null) {
            throw new ResourceNotFoundException("原始运行不存在");
        }
        if (original.status().isActive()) {
            throw new IllegalStateException("原始运行尚未结束，不能重新生成");
        }
        List<String> selectedKnowledgeBaseIds = runs.selectedKnowledgeBaseIds(originalRunId);
        ValidatedCommand validated = validate(new SubmitRunCommand(original.question(), original.mode(), selectedKnowledgeBaseIds, idempotencyKey));
        AgentRun existing = runs.findByUserAndIdempotencyKey(userId, validated.idempotencyKey());
        if (existing != null) {
            return sameRequestOrConflict(existing, validated.requestHash());
        }
        if (runs.hasActiveRun(conversationId)) {
            throw new ActiveRunConflictException();
        }
        AgentRun created = newRun(userId, conversationId, validated, originalRunId);
        try {
            runs.create(created);
        } catch (DataIntegrityViolationException exception) {
            AgentRun duplicate = runs.findByUserAndIdempotencyKey(userId, validated.idempotencyKey());
            if (duplicate != null) {
                return sameRequestOrConflict(duplicate, validated.requestHash());
            }
            throw new ActiveRunConflictException();
        }
        dispatch.enqueue(created, validated.selectedKnowledgeBaseIds());
        return new RunAcceptance(created, false);
    }

    private AgentRun newRun(UUID userId, UUID conversationId, ValidatedCommand command, UUID regenerationOfRunId) {
        return new AgentRun(UUID.randomUUID(), UUID.randomUUID(), userId, conversationId, command.mode(),
                RunStatus.CREATED, command.question(), command.idempotencyKey(), command.requestHash(),
                regenerationOfRunId == null ? UUID.randomUUID() : null, regenerationOfRunId, Instant.now());
    }

    private RunAcceptance sameRequestOrConflict(AgentRun existing, String requestHash) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException();
        }
        return new RunAcceptance(existing, true);
    }

    private Conversation requiredConversation(UUID userId, UUID conversationId) {
        Conversation conversation = conversations.findOwned(userId, conversationId);
        if (conversation == null) {
            throw new ResourceNotFoundException("会话不存在");
        }
        return conversation;
    }

    private static String requiredText(String input, String label, int maxLength) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String normalized = input.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(label + "长度超限");
        }
        return normalized;
    }

    private static String titleFromFirstQuestion(String question) {
        String normalized = question.replaceAll("\\s+", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= 255) return normalized;
        return normalized.substring(0, normalized.offsetByCodePoints(0, 255));
    }

    private static int pageSize(Integer requested) {
        int value = requested == null ? DEFAULT_PAGE_SIZE : requested;
        if (value < 1 || value > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("分页大小必须在 1 到 100 之间");
        }
        return value;
    }

    private static ConversationCursor conversationCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        CursorCodec.CursorValue value = CursorCodec.decode(cursor);
        return new ConversationCursor(value.createdAt(), value.id());
    }

    private static MessageCursor messageCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        CursorCodec.CursorValue value = CursorCodec.decode(cursor);
        return new MessageCursor(value.createdAt(), value.id());
    }

    private static ValidatedCommand validate(SubmitRunCommand command) {
        if (command == null || command.mode() == null) {
            throw new IllegalArgumentException("执行模式不能为空");
        }
        String question = requiredText(command.question(), "问题", 10_000);
        String key = requiredText(command.idempotencyKey(), "Idempotency-Key", 128);
        List<String> knowledgeBaseIds = command.selectedKnowledgeBaseIds() == null ? List.of() : List.copyOf(command.selectedKnowledgeBaseIds());
        if (knowledgeBaseIds.size() > 5 || knowledgeBaseIds.stream().anyMatch(value -> value == null || value.isBlank())
                || knowledgeBaseIds.stream().distinct().count() != knowledgeBaseIds.size()) {
            throw new IllegalArgumentException("知识库选择无效");
        }
        try {
            knowledgeBaseIds.forEach(UUID::fromString);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("知识库标识无效", exception);
        }
        if (command.mode() == RunMode.MANUAL_KB && knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("自选知识模式必须选择知识库");
        }
        if (command.mode() != RunMode.MANUAL_KB && !knowledgeBaseIds.isEmpty()) {
            throw new IllegalArgumentException("仅自选知识模式可以指定知识库");
        }
        return new ValidatedCommand(question, command.mode(), knowledgeBaseIds, key,
                hash(question + "\u001F" + command.mode().name() + "\u001F" + String.join(",", knowledgeBaseIds)));
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private record ValidatedCommand(String question, RunMode mode, List<String> selectedKnowledgeBaseIds,
                                    String idempotencyKey, String requestHash) {
    }
}
