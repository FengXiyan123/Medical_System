package com.feng.medical.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Staged debug payload; raw scores retain their score type for interpretation. */
public record RetrievalTestResult(
        @JsonProperty("rewritten_query") String rewrittenQuery,
        List<RetrievalTestHit> recalled,
        List<RetrievalTestHit> reranked,
        List<RetrievalTestContext> context,
        @JsonProperty("reranker_status") String rerankerStatus,
        @JsonProperty("reranked_score_type") String rerankedScoreType,
        @JsonProperty("usage_purpose") String usagePurpose) {
    public RetrievalTestResult {
        recalled = List.copyOf(recalled); reranked = List.copyOf(reranked); context = List.copyOf(context);
    }

    public record RetrievalTestHit(String stage, int rank,
                                   @JsonProperty("knowledge_base_id") String knowledgeBaseId,
                                   @JsonProperty("generation_id") String generationId,
                                   @JsonProperty("chunk_id") String chunkId,
                                   @JsonProperty("raw_score") double rawScore,
                                   @JsonProperty("score_type") String scoreType,
                                   @JsonProperty("text_snapshot") String textSnapshot) { }
    public record RetrievalTestContext(@JsonProperty("citation_id") String citationId,
                                       @JsonProperty("knowledge_base_id") String knowledgeBaseId,
                                       @JsonProperty("generation_id") String generationId,
                                       @JsonProperty("chunk_id") String chunkId,
                                       @JsonProperty("token_count") int tokenCount, String content) { }
}
