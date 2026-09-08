import { describe, expect, it } from "vitest";

import { applyRunEvent, createRunStreamState } from "./runStream";

describe("run stream state", () => {
  it("deduplicates replayed events and accumulates answer deltas", () => {
    const state = createRunStreamState();
    applyRunEvent(state, { id: "1", type: "answer.delta", data: { text: "您好，" } });
    applyRunEvent(state, { id: "1", type: "answer.delta", data: { text: "重复" } });
    applyRunEvent(state, { id: "2", type: "answer.delta", data: { text: "请注意休息。" } });
    applyRunEvent(state, { id: "3", type: "run.completed", data: { citations: [{ title: "宣教资料" }] } });

    expect(state.answer.value).toBe("您好，请注意休息。");
    expect(state.lastEventId.value).toBe("3");
    expect(state.status.value).toBe("SUCCEEDED");
    expect(state.citations.value).toEqual([{ title: "宣教资料" }]);
  });

  it("moves a stopped request to cancelled without treating it as a transport error", () => {
    const state = createRunStreamState();
    applyRunEvent(state, { id: "4", type: "run.cancelled", data: {} });
    expect(state.status.value).toBe("CANCELLED");
    expect(state.error.value).toBeNull();
  });

  it("keeps route choices, tool steps, and a budget stop reason for the execution drawer", () => {
    const state = createRunStreamState();

    applyRunEvent(state, {
      id: "route-1", type: "route.selected", data: {
        candidates: [{ id: "kb-a", name: "高血压宣教", version: "g-3" }],
        selected_knowledge_base_ids: ["kb-a"],
        reason: "问题涉及高血压居家管理",
        selection_reason: { "kb-a": "覆盖血压监测和居家管理" }
      }
    });
    applyRunEvent(state, {
      id: "tool-1", type: "tool.started", data: {
        tool_call_id: "call-1", tool_name: "knowledge_search", input_summary: "查询：血压监测"
      }
    });
    applyRunEvent(state, {
      id: "tool-2", type: "tool.completed", data: {
        tool_call_id: "call-1", tool_name: "knowledge_search", output_summary: "命中 3 个切块"
      }
    });
    applyRunEvent(state, {
      id: "end", type: "run.completed", data: {
        answer: "", summary: { finish_reason: "AGENT_DECISION_LIMIT" }
      }
    });

    expect(state.route.value?.selectedKnowledgeBaseIds).toEqual(["kb-a"]);
    expect(state.route.value?.reason).toBe("问题涉及高血压居家管理");
    expect(state.route.value?.selectionReasons).toEqual({ "kb-a": "覆盖血压监测和居家管理" });
    expect(state.steps.value).toContainEqual(expect.objectContaining({
      id: "tool:call-1", label: "knowledge_search", status: "COMPLETED", detail: "命中 3 个切块"
    }));
    expect(state.budgetTermination.value).toBe("已达到 Agent 决策次数上限");
  });

  it("turns a node failure into a concise, display-safe step", () => {
    const state = createRunStreamState();
    applyRunEvent(state, {
      id: "failed", type: "node.failed", data: {
        node_id: "retrieval", node_name: "检索", message: "连接超时\n不应展示堆栈"
      }
    });

    expect(state.steps.value).toEqual([{
      id: "node:retrieval", kind: "NODE", label: "检索", status: "FAILED", detail: "连接超时 不应展示堆栈"
    }]);
  });

  it("translates every M4 quota termination without exposing raw internal codes", () => {
    const cases = [
      ["TOOL_CALL_LIMIT", "已达到工具调用次数上限"],
      ["DEADLINE", "已达到本轮执行时间上限"],
      ["BUDGET_EXHAUSTED", "已达到本轮模型调用或 Token 预算"],
      ["DECISION_INVALID", "Agent 决策无效，已停止执行"]
    ] as const;

    for (const [finishReason, expected] of cases) {
      const state = createRunStreamState();
      applyRunEvent(state, { id: finishReason, type: "run.summary", data: { finish_reason: finishReason } });
      expect(state.budgetTermination.value).toBe(expected);
    }
  });
});
