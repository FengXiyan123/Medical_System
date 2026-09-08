import { describe, expect, it, vi } from "vitest";

import { ChatApi } from "./api";

const session = {
  accessToken: "access-token",
  refreshToken: "refresh-token",
  user: { id: "user-1", username: "demo", role: "USER" as const }
};

describe("ChatApi.submitRun", () => {
  it("keeps a unique knowledge-base selection only for manual knowledge runs", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({
      run: { id: "run-1", status: "QUEUED" }, reused: false
    }), { status: 202, headers: { "Content-Type": "application/json" } })));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => "request-1" });
    const api = new ChatApi(session);

    await api.submitRun("conversation-1", "如何监测血压？", "MANUAL_KB", ["kb-1", "kb-1", "kb-2"]);
    await api.submitRun("conversation-1", "你好", "AUTO_KB", ["kb-1"]);
    await api.submitRun("conversation-1", "帮我计算 BMI", "AGENT", ["kb-1"]);

    const bodies = fetchMock.mock.calls.map((call) => JSON.parse(call[1].body));
    expect(bodies).toEqual([
      { question: "如何监测血压？", mode: "MANUAL_KB", selectedKnowledgeBaseIds: ["kb-1", "kb-2"] },
      { question: "你好", mode: "AUTO_KB", selectedKnowledgeBaseIds: [] },
      { question: "帮我计算 BMI", mode: "AGENT", selectedKnowledgeBaseIds: [] }
    ]);
  });
});

describe("ChatApi.deleteConversation", () => {
  it("archives a conversation through the authenticated delete endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await new ChatApi(session).deleteConversation("conversation-1");

    expect(fetchMock).toHaveBeenCalledWith("/api/v1/conversations/conversation-1", expect.objectContaining({
      method: "DELETE",
      headers: expect.objectContaining({ Authorization: "Bearer access-token" })
    }));
  });
});
