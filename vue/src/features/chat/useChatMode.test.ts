import { describe, expect, it } from "vitest";

import { createChatModeState } from "./useChatMode";

describe("createChatModeState", () => {
  it("keeps knowledge-base selection only in manual mode", () => {
    const state = createChatModeState();

    state.selectMode("MANUAL_KB");
    state.setKnowledgeBaseIds(["kb-guideline", "kb-demo"]);

    expect(state.request.value).toEqual({
      mode: "MANUAL_KB",
      knowledge_base_ids: ["kb-guideline", "kb-demo"]
    });

    state.selectMode("AGENT");

    expect(state.selectedKnowledgeBaseIds.value).toEqual([]);
    expect(state.request.value).toEqual({ mode: "AGENT" });
  });

  it("removes duplicate selected knowledge bases", () => {
    const state = createChatModeState();

    state.selectMode("MANUAL_KB");
    state.setKnowledgeBaseIds(["kb-guideline", "kb-guideline", "kb-demo"]);

    expect(state.selectedKnowledgeBaseIds.value).toEqual(["kb-guideline", "kb-demo"]);
  });
});
