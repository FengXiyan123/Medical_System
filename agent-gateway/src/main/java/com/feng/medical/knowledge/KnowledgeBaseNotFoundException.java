package com.feng.medical.knowledge;

public class KnowledgeBaseNotFoundException extends RuntimeException {
    public KnowledgeBaseNotFoundException() { super("知识库不存在"); }
}
