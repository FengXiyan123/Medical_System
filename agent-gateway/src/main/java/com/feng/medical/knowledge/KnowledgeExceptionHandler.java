package com.feng.medical.knowledge;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {ChunkController.class, PublicationController.class,
        KnowledgeScopeController.class, RetrievalTestController.class})
public class KnowledgeExceptionHandler {
    @ExceptionHandler({PublicationConflictException.class, DraftEditConflictException.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict(RuntimeException exception) {
        return Map.of("code", "EDIT_OR_PUBLICATION_CONFLICT", "message", exception.getMessage());
    }

    @ExceptionHandler(GenerationNotReadyException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> generationNotReady(GenerationNotReadyException exception) {
        return Map.of("code", "GENERATION_NOT_READY", "message", exception.getMessage());
    }

    @ExceptionHandler(KnowledgeSelectionRequiredException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public Map<String, String> selectionRequired(KnowledgeSelectionRequiredException exception) {
        return Map.of("code", "KNOWLEDGE_SELECTION_REQUIRED", "message", exception.getMessage());
    }

    @ExceptionHandler(KnowledgeScopeForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public Map<String, String> scopeForbidden(KnowledgeScopeForbiddenException exception) {
        return Map.of("code", "KNOWLEDGE_SCOPE_FORBIDDEN", "message", exception.getMessage());
    }
}
