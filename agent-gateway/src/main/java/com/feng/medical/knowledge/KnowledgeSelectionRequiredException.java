package com.feng.medical.knowledge;

public class KnowledgeSelectionRequiredException extends RuntimeException {
    public KnowledgeSelectionRequiredException() {
        super("KNOWLEDGE_SELECTION_REQUIRED: 自选知识模式必须选择知识库");
    }
}
