package com.feng.medical.knowledge;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.feng.medical.streaming.ServiceTokenIssuer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Java gateway adapter for the Python service that owns draft chunks and pgvector. */
@Component
public class HttpChunkWorkspacePort implements ChunkWorkspacePort {
    private final RestClient client;
    private final ServiceTokenIssuer tokens;

    public HttpChunkWorkspacePort(AgentCoreKnowledgeProperties properties, ServiceTokenIssuer tokens) {
        client = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(new SimpleClientHttpRequestFactory()).build();
        this.tokens = tokens;
    }

    @Override
    public AdminChunkPage list(UUID generationId, String query, Boolean enabled, int offset, int limit) {
        try {
            AgentCoreChunkPage page = client.get().uri(uri -> uri.path("/internal/generations/{id}/chunks")
                    .queryParamIfPresent("query", java.util.Optional.ofNullable(query))
                    .queryParamIfPresent("enabled", java.util.Optional.ofNullable(enabled))
                    .queryParam("offset", offset).queryParam("limit", limit).build(generationId))
                    .header("Authorization", "Bearer " + tokens.coreTicket("chunks:read")).retrieve().body(AgentCoreChunkPage.class);
            return page.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    @Override
    public AdminChunk get(UUID generationId, UUID chunkId) {
        try {
            AgentCoreChunk chunk = client.get().uri("/internal/generations/{generationId}/chunks/{chunkId}", generationId, chunkId)
                    .header("Authorization", "Bearer " + tokens.coreTicket("chunks:read")).retrieve().body(AgentCoreChunk.class);
            return chunk.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    @Override
    public GenerationWorkspace update(UUID generationId, UUID chunkId, String content, Boolean enabled, long expectedEditRevision) {
        return mutate("/internal/generations/{generationId}/chunks/{chunkId}", generationId, chunkId,
                Map.of("expected_edit_revision", expectedEditRevision, content != null ? "content" : "enabled", content != null ? content : enabled));
    }

    @Override
    public GenerationWorkspace split(UUID generationId, UUID chunkId, int atCharacter, long expectedEditRevision) {
        try {
            AgentCoreWorkspace workspace = client.post().uri("/internal/generations/{generationId}/chunks/{chunkId}/split", generationId, chunkId)
                    .body(Map.of("at_character", atCharacter, "expected_edit_revision", expectedEditRevision))
                    .header("Authorization", "Bearer " + tokens.coreTicket("chunks:write")).retrieve().body(AgentCoreWorkspace.class);
            return workspace.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    @Override
    public GenerationWorkspace merge(UUID generationId, UUID firstChunkId, UUID secondChunkId, long expectedEditRevision) {
        try {
            AgentCoreWorkspace workspace = client.post().uri("/internal/generations/{generationId}/chunks:merge", generationId)
                    .body(Map.of("first_chunk_id", firstChunkId, "second_chunk_id", secondChunkId,
                            "expected_edit_revision", expectedEditRevision))
                    .header("Authorization", "Bearer " + tokens.coreTicket("chunks:write")).retrieve().body(AgentCoreWorkspace.class);
            return workspace.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    @Override
    public GenerationBuildSummary index(UUID generationId) {
        try {
            AgentCoreManifest manifest = client.post().uri("/internal/generations/{generationId}/index", generationId)
                    .header("Authorization", "Bearer " + tokens.coreTicket("chunks:write")).retrieve().body(AgentCoreManifest.class);
            return manifest.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    @Override
    public GenerationBuildSummary getBuild(UUID generationId) {
        try {
            AgentCoreManifest manifest = client.get().uri("/internal/generations/{generationId}/manifest", generationId)
                    .header("Authorization", "Bearer " + tokens.coreTicket("chunks:read")).retrieve().body(AgentCoreManifest.class);
            return manifest.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    private GenerationWorkspace mutate(String path, UUID generationId, UUID chunkId, Map<String, Object> payload) {
        try {
            AgentCoreWorkspace workspace = client.patch().uri(path, generationId, chunkId).header("Authorization", "Bearer " + tokens.coreTicket("chunks:write")).body(payload)
                    .retrieve().body(AgentCoreWorkspace.class);
            return workspace.toDomain();
        } catch (RestClientResponseException error) {
            throw translate(error);
        }
    }

    private RuntimeException translate(RestClientResponseException error) {
        HttpStatusCode status = error.getStatusCode();
        if (status.value() == 409) return new DraftEditConflictException(error.getResponseBodyAsString());
        if (status.value() == 404) return new IllegalArgumentException("generation or chunk does not exist");
        return new IllegalStateException("agent-core knowledge request failed: " + status.value(), error);
    }

    private record AgentCoreChunk(
            String id, int ordinal, String content, boolean enabled,
            @JsonProperty("page_start") Integer pageStart, @JsonProperty("page_end") Integer pageEnd,
            @JsonProperty("section_path") String sectionPath, @JsonProperty("manually_edited") boolean manuallyEdited,
            @JsonProperty("content_hash") String contentHash
    ) {
        AdminChunk toDomain() { return new AdminChunk(UUID.fromString(id), ordinal, content, enabled, pageStart, pageEnd, sectionPath, manuallyEdited, contentHash); }
    }

    private record AgentCoreChunkPage(
            List<AgentCoreChunk> chunks, int total, @JsonProperty("next_offset") Integer nextOffset,
            @JsonProperty("edit_revision") long editRevision, @JsonProperty("generation_state") String generationState
    ) {
        AdminChunkPage toDomain() { return new AdminChunkPage(chunks.stream().map(AgentCoreChunk::toDomain).toList(), total, nextOffset, editRevision, generationState); }
    }

    private record AgentCoreWorkspace(String id, String state, @JsonProperty("edit_revision") long editRevision,
                                      List<AgentCoreChunk> chunks) {
        GenerationWorkspace toDomain() { return new GenerationWorkspace(UUID.fromString(id), state, editRevision, chunks.stream().map(AgentCoreChunk::toDomain).toList()); }
    }

    private record AgentCoreManifest(
            @JsonProperty("generation_id") String generationId, @JsonProperty("build_status") GenerationBuildStatus buildStatus,
            @JsonProperty("document_id") String documentId,
            @JsonProperty("manifest_hash") String manifestHash, @JsonProperty("chunk_count") int chunkCount,
            @JsonProperty("embedding_profile_id") String embeddingProfileId,
            @JsonProperty("embedding_dimension") int embeddingDimension
    ) {
        GenerationBuildSummary toDomain() { return new GenerationBuildSummary(UUID.fromString(generationId), UUID.fromString(documentId), buildStatus, manifestHash, chunkCount, embeddingProfileId, embeddingDimension); }
    }
}
