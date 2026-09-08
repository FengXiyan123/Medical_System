import { describe, expect, it } from "vitest";
import { createRetrievalLabState } from "./retrievalLab";

describe("retrieval lab presentation state", () => {
  it("keeps empty, forbidden, failed and ready states distinct so scores are never invented", () => {
    const state = createRetrievalLabState();
    expect(state.status.value).toBe("IDLE");
    state.start();
    state.empty();
    expect(state.emptyMessage.value).toContain("没有召回");
    state.forbidden("没有权限访问所选知识库");
    expect(state.status.value).toBe("FORBIDDEN");
    state.failed("服务暂不可用");
    expect(state.canRetry.value).toBe(true);
    state.ready({ rerankerStatus: "NOT_CONFIGURED" });
    expect(state.rerankerMessage.value).toContain("未启用");
  });
});
