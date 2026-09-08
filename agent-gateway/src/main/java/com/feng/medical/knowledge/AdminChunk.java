package com.feng.medical.knowledge;

import java.util.UUID;

public record AdminChunk(UUID id, int ordinal, String content, boolean enabled, Integer pageStart, Integer pageEnd,
                         String sectionPath, boolean manuallyEdited, String contentHash) {
}
