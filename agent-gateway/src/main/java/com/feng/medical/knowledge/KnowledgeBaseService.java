package com.feng.medical.knowledge;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeBaseService {
    private final KnowledgeBaseRepository repository;
    private final Clock clock;

    public KnowledgeBaseService(KnowledgeBaseRepository repository) { this(repository, Clock.systemUTC()); }
    @Autowired
    KnowledgeBaseService(KnowledgeBaseRepository repository, Clock clock) { this.repository = repository; this.clock = clock; }

    @Transactional
    public KnowledgeBase create(UUID creatorId, CreateKnowledgeBaseCommand command) {
        ValidatedCommand validated = validate(command);
        Instant now = clock.instant();
        KnowledgeBase created = repository.create(new KnowledgeBase(UUID.randomUUID(), validated.name(), validated.description(),
                KnowledgeBaseStatus.DRAFT, validated.scope(), creatorId, now, now));
        replaceGrants(created.id(), validated.scope(), validated.assignedUserIds(), creatorId);
        return created;
    }

    @Transactional(readOnly = true) public KnowledgeBase get(UUID id) { return required(id); }
    @Transactional(readOnly = true) public List<KnowledgeBase> listAll() { return repository.listAll(); }
    @Transactional(readOnly = true) public List<KnowledgeBase> listAccessible(UUID userId) { return repository.listAccessible(userId); }
    @Transactional(readOnly = true) public boolean canAccess(UUID userId, UUID knowledgeBaseId) { return repository.canAccess(userId, knowledgeBaseId); }

    @Transactional
    public KnowledgeBase update(UUID id, UUID administratorId, CreateKnowledgeBaseCommand command) {
        KnowledgeBase current = required(id);
        ValidatedCommand validated = validate(command);
        KnowledgeBase updated = new KnowledgeBase(current.id(), validated.name(), validated.description(), current.status(),
                validated.scope(), current.createdBy(), current.createdAt(), clock.instant());
        repository.update(updated);
        replaceGrants(id, validated.scope(), validated.assignedUserIds(), administratorId);
        return updated;
    }

    @Transactional
    public KnowledgeBase changeStatus(UUID id, KnowledgeBaseStatus status) {
        if (status == null) throw new IllegalArgumentException("知识库状态不能为空");
        KnowledgeBase current = required(id);
        KnowledgeBase updated = new KnowledgeBase(current.id(), current.name(), current.description(), status,
                current.accessScope(), current.createdBy(), current.createdAt(), clock.instant());
        return repository.update(updated);
    }

    private KnowledgeBase required(UUID id) {
        KnowledgeBase result = repository.findById(id);
        if (result == null) throw new KnowledgeBaseNotFoundException();
        return result;
    }
    private void replaceGrants(UUID id, KnowledgeAccessScope scope, List<UUID> ids, UUID administratorId) {
        repository.replaceGrants(id, scope == KnowledgeAccessScope.ASSIGNED_USERS ? ids : List.of(), administratorId);
    }
    private static ValidatedCommand validate(CreateKnowledgeBaseCommand command) {
        if (command == null || command.accessScope() == null) throw new IllegalArgumentException("知识库授权范围不能为空");
        String name = text(command.name(), "知识库名称", 128);
        String description = optionalText(command.description(), "知识库简介", 500);
        List<UUID> ids = command.assignedUserIds() == null ? List.of() : List.copyOf(command.assignedUserIds());
        if (ids.stream().anyMatch(java.util.Objects::isNull) || ids.stream().distinct().count() != ids.size()) throw new IllegalArgumentException("授权用户无效");
        if (command.accessScope() == KnowledgeAccessScope.ALL_AUTHENTICATED && !ids.isEmpty()) throw new IllegalArgumentException("ALL_AUTHENTICATED 不能设置显式授权用户");
        if (command.accessScope() == KnowledgeAccessScope.ASSIGNED_USERS && ids.isEmpty()) throw new IllegalArgumentException("ASSIGNED_USERS 至少需要一名授权用户");
        return new ValidatedCommand(name, description, command.accessScope(), ids);
    }
    private static String text(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "不能为空");
        String normalized = value.trim(); if (normalized.length() > max) throw new IllegalArgumentException(name + "长度超限"); return normalized;
    }
    private static String optionalText(String value, String name, int max) {
        if (value == null || value.isBlank()) return "";
        return text(value, name, max);
    }
    private record ValidatedCommand(String name, String description, KnowledgeAccessScope scope, List<UUID> assignedUserIds) { }
}
