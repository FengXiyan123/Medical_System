package com.feng.medical.knowledge;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** External ADMIN-only chunk management routes; Python retains ownership of chunk data. */
@RestController
@RequestMapping("/api/admin/generations/{generationId}")
public class ChunkController {
    private final ChunkWorkspacePort chunks;

    public ChunkController(ChunkWorkspacePort chunks) {
        this.chunks = chunks;
    }

    @GetMapping("/chunks")
    public AdminChunkPage list(@PathVariable UUID generationId, @RequestParam(required = false) String query,
                               @RequestParam(required = false) Boolean enabled,
                               @RequestParam(defaultValue = "0") @Min(0) int offset,
                               @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return chunks.list(generationId, query, enabled, offset, limit);
    }

    @GetMapping("/chunks/{chunkId}")
    public AdminChunk detail(@PathVariable UUID generationId, @PathVariable UUID chunkId) {
        return chunks.get(generationId, chunkId);
    }

    @PatchMapping("/chunks/{chunkId}")
    public GenerationWorkspace update(@PathVariable UUID generationId, @PathVariable UUID chunkId,
                                      @Valid @RequestBody ChunkUpdateRequest request) {
        if ((request.content() == null) == (request.enabled() == null)) {
            throw new IllegalArgumentException("正文或启用状态必须且只能提供一个");
        }
        return chunks.update(generationId, chunkId, request.content(), request.enabled(), request.expectedEditRevision());
    }

    @PostMapping("/chunks/{chunkId}/split")
    public GenerationWorkspace split(@PathVariable UUID generationId, @PathVariable UUID chunkId,
                                     @Valid @RequestBody SplitRequest request) {
        return chunks.split(generationId, chunkId, request.atCharacter(), request.expectedEditRevision());
    }

    @PostMapping("/chunks:merge")
    public GenerationWorkspace merge(@PathVariable UUID generationId, @Valid @RequestBody MergeRequest request) {
        return chunks.merge(generationId, request.firstChunkId(), request.secondChunkId(), request.expectedEditRevision());
    }

    @PostMapping("/index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationBuildSummary index(@PathVariable UUID generationId) {
        return chunks.index(generationId);
    }

    public record ChunkUpdateRequest(@Size(max = 1_000_000) String content, Boolean enabled,
                                     @NotNull @Min(0) Long expectedEditRevision) {
    }

    public record SplitRequest(@Min(1) int atCharacter, @NotNull @Min(0) Long expectedEditRevision) {
    }

    public record MergeRequest(@NotNull UUID firstChunkId, @NotNull UUID secondChunkId,
                               @NotNull @Min(0) Long expectedEditRevision) {
    }
}
