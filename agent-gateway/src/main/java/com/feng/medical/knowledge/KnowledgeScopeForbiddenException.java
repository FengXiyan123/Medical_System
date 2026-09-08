package com.feng.medical.knowledge;

public class KnowledgeScopeForbiddenException extends RuntimeException {
    public KnowledgeScopeForbiddenException() {
        super("KNOWLEDGE_SCOPE_FORBIDDEN: 请求的知识库不在当前授权范围内");
    }
}
