package com.feng.medical.knowledge;

import java.util.List;

public record AdminChunkPage(List<AdminChunk> chunks, int total, Integer nextOffset, long editRevision,
                             String generationState) {
}
