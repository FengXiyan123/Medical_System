import { describe, expect, it } from "vitest";

import { createChunkWorkspaceState, type GenerationChunk } from "./chunkWorkspace";

const chunks: GenerationChunk[] = [
  { id: "c-1", ordinal: 0, content: "第一段", enabled: true, manuallyEdited: false, pageStart: 1 },
  { id: "c-2", ordinal: 1, content: "第二段", enabled: false, manuallyEdited: true, pageStart: 2 }
];

describe("createChunkWorkspaceState", () => {
  it("keeps the server edit revision and warns after an optimistic-lock conflict", () => {
    const state = createChunkWorkspaceState({ state: "DRAFT", editRevision: 3, chunks });

    state.applyServerGeneration({ state: "DRAFT", editRevision: 4, chunks });
    state.applyEditConflict();

    expect(state.editRevision.value).toBe(4);
    expect(state.needsRefresh.value).toBe(true);
    expect(state.isReadOnly.value).toBe(false);
  });

  it("makes published generations read-only and preserves source-page metadata", () => {
    const state = createChunkWorkspaceState({ state: "ACTIVE", editRevision: 8, chunks });

    expect(state.isReadOnly.value).toBe(true);
    expect(state.visibleChunks.value[1].pageStart).toBe(2);
    expect(state.draftNotice.value).toContain("只读");
  });
});
