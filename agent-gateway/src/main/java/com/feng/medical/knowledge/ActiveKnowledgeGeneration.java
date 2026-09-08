package com.feng.medical.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** One currently published generation the caller is permitted to retrieve. */
public record ActiveKnowledgeGeneration(@JsonProperty("knowledge_base_id") UUID knowledgeBaseId,
                                        @JsonProperty("document_id") UUID documentId,
                                        @JsonProperty("generation_id") UUID generationId,
                                        @JsonProperty("authorization_version") long authorizationVersion) {
    public ActiveKnowledgeGeneration {
        if (knowledgeBaseId == null || documentId == null || generationId == null) {
            throw new IllegalArgumentException("知识范围标识不能为空");
        }
        if (authorizationVersion < 0) {
            throw new IllegalArgumentException("授权版本不能为负数");
        }
    }
}
