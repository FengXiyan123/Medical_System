package com.feng.medical.knowledge;

import java.util.List;
import java.util.UUID;

public record GenerationWorkspace(UUID id, String state, long editRevision, List<AdminChunk> chunks) {
}
