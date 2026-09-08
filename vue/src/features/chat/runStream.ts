import { ref } from "vue";
import type { Ref } from "vue";

export type RunStreamStatus = "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED";
export interface Citation { title?: string; [key: string]: unknown; }
export interface RunStreamEvent { id: string; type: string; data: Record<string, unknown>; }
export interface RouteCandidate {
  id: string;
  name: string;
  version: string | null;
}
export interface RouteSelection {
  candidates: RouteCandidate[];
  selectedKnowledgeBaseIds: string[];
  reason: string | null;
  selectionReasons: Record<string, string>;
  fallbackUsed: boolean;
}
export type ExecutionStepKind = "ROUTE" | "NODE" | "TOOL";
export type ExecutionStepStatus = "RUNNING" | "COMPLETED" | "FAILED";
/** Public execution metadata. Model hidden reasoning is deliberately never represented here. */
export interface ExecutionStep {
  id: string;
  kind: ExecutionStepKind;
  label: string;
  status: ExecutionStepStatus;
  detail: string | null;
}
export interface RunStreamState {
  answer: Ref<string>;
  citations: Ref<Citation[]>;
  summary: Ref<Record<string, unknown> | null>;
  status: Ref<RunStreamStatus>;
  error: Ref<string | null>;
  lastEventId: Ref<string | null>;
  seenEventIds: Set<string>;
  route: Ref<RouteSelection | null>;
  steps: Ref<ExecutionStep[]>;
  budgetTermination: Ref<string | null>;
}

export function createRunStreamState(): RunStreamState {
  return {
    answer: ref(""), citations: ref([]), summary: ref(null), status: ref("IDLE"), error: ref(null),
    lastEventId: ref(null), seenEventIds: new Set(), route: ref(null), steps: ref([]), budgetTermination: ref(null)
  };
}

/** Applies replays safely: event ids and SSE sequence ids are both deduplicated client-side. */
export function applyRunEvent(state: RunStreamState, event: RunStreamEvent): boolean {
  if (!event.id || state.seenEventIds.has(event.id)) return false;
  state.seenEventIds.add(event.id);
  state.lastEventId.value = event.id;
  switch (event.type) {
    case "run.started": state.status.value = "RUNNING"; break;
    case "route.selected":
      state.route.value = readRouteSelection(event.data);
      upsertStep(state, {
        id: "route:selected", kind: "ROUTE", label: "自动知识路由", status: "COMPLETED",
        detail: state.route.value.reason
      });
      break;
    case "node.started": upsertStep(state, readNodeStep(event, "RUNNING")); break;
    case "node.completed": upsertStep(state, readNodeStep(event, "COMPLETED")); break;
    case "node.failed": upsertStep(state, readNodeStep(event, "FAILED")); break;
    case "tool.started": upsertStep(state, readToolStep(event, "RUNNING")); break;
    case "tool.completed": upsertStep(state, readToolStep(event, "COMPLETED")); break;
    case "tool.failed": upsertStep(state, readToolStep(event, "FAILED")); break;
    case "answer.delta": state.answer.value += String(event.data.text ?? ""); break;
    case "run.citations": state.citations.value = readCitations(event.data); break;
    case "citation.ready":
      state.citations.value = readCitations(event.data, state.citations.value);
      break;
    case "retrieval.completed":
      upsertStep(state, readRetrievalStep(event));
      break;
    case "usage.updated":
      state.summary.value = { ...(state.summary.value ?? {}), usage: event.data };
      break;
    case "run.summary":
      state.summary.value = event.data;
      state.budgetTermination.value = budgetTerminationOf(event.data);
      break;
    case "run.completed":
      if (typeof event.data.answer === "string" && !state.answer.value) state.answer.value = event.data.answer;
      state.citations.value = readCitations(event.data, state.citations.value);
      state.summary.value = event.data.summary && typeof event.data.summary === "object" ? event.data.summary as Record<string, unknown> : state.summary.value;
      state.budgetTermination.value = budgetTerminationOf(state.summary.value ?? event.data);
      state.status.value = "SUCCEEDED"; break;
    case "run.failed":
      state.status.value = "FAILED";
      state.error.value = conciseText(event.data.message ?? "运行失败");
      state.budgetTermination.value = budgetTerminationOf(event.data);
      break;
    case "run.cancelled": state.status.value = "CANCELLED"; break;
  }
  return true;
}

function readRouteSelection(data: Record<string, unknown>): RouteSelection {
  const candidates = Array.isArray(data.candidates)
    ? data.candidates.flatMap((candidate): RouteCandidate[] => {
      if (!candidate || typeof candidate !== "object") return [];
      const value = candidate as Record<string, unknown>;
      const id = textValue(value.id ?? value.knowledge_base_id);
      if (!id) return [];
      return [{ id, name: textValue(value.name ?? value.title) || id, version: textValue(value.version ?? value.generation_id) || null }];
    }) : [];
  const selected = data.selected_knowledge_base_ids ?? data.selectedKnowledgeBaseIds ?? data.selected_ids;
  const reasonValue = data.reason ?? data.selection_reason;
  const selectionReasons = objectTextValues(
    typeof data.selection_reason === "object" && data.selection_reason !== null ? data.selection_reason : data.selection_reasons
  );
  return {
    candidates,
    selectedKnowledgeBaseIds: Array.isArray(selected) ? selected.flatMap(value => textValue(value) ? [textValue(value)] : []) : [],
    reason: textValue(reasonValue) || null,
    selectionReasons,
    fallbackUsed: data.fallback_used === true || data.fallbackUsed === true
  };
}

function readNodeStep(event: RunStreamEvent, status: ExecutionStepStatus): ExecutionStep {
  const nodeId = textValue(event.data.node_id ?? event.data.nodeId) || event.id;
  return {
    id: `node:${nodeId}`, kind: "NODE", label: textValue(event.data.node_name ?? event.data.nodeName ?? event.data.node) || "执行节点",
    status, detail: conciseText(event.data.message ?? event.data.output_summary ?? event.data.input_summary) || null
  };
}

function readToolStep(event: RunStreamEvent, status: ExecutionStepStatus): ExecutionStep {
  const callId = identifierValue(event.data.tool_call_id ?? event.data.toolCallId ?? event.data.sequence) || event.id;
  return {
    id: `tool:${callId}`, kind: "TOOL", label: textValue(event.data.tool_name ?? event.data.toolName ?? event.data.tool) || "工具调用",
    status, detail: conciseText(event.data.output_summary ?? event.data.input_summary ?? event.data.result ?? event.data.error_code ?? event.data.message) || null
  };
}

function readRetrievalStep(event: RunStreamEvent): ExecutionStep {
  const hits = Array.isArray(event.data.citations) ? event.data.citations.length : textValue(event.data.hit_count ?? event.data.hitCount);
  return {
    id: `node:${textValue(event.data.node_id ?? event.data.nodeId) || "retrieval"}`,
    kind: "NODE", label: "知识检索", status: "COMPLETED",
    detail: hits ? `已获得 ${hits} 条候选资料` : conciseText(event.data.summary) || null
  };
}

function upsertStep(state: RunStreamState, step: ExecutionStep): void {
  const current = state.steps.value.findIndex(item => item.id === step.id);
  if (current < 0) state.steps.value = [...state.steps.value, step];
  else state.steps.value = state.steps.value.map((item, index) => index === current ? { ...item, ...step, detail: step.detail ?? item.detail } : item);
}

function budgetTerminationOf(data: Record<string, unknown>): string | null {
  const reason = textValue(data.finish_reason ?? data.finishReason ?? data.reason ?? data.code);
  return ({
    AGENT_DECISION_LIMIT: "已达到 Agent 决策次数上限",
    TOOL_CALL_LIMIT: "已达到工具调用次数上限",
    EXECUTION_LIMIT: "已达到 Agent 或工具执行上限",
    BUDGET_EXHAUSTED: "已达到本轮模型调用或 Token 预算",
    DEADLINE_EXCEEDED: "已达到本轮执行时间上限",
    DEADLINE: "已达到本轮执行时间上限",
    CONTEXT_BUDGET_EXHAUSTED: "资料上下文已达到 Token 预算",
    DECISION_INVALID: "Agent 决策无效，已停止执行"
  } as Record<string, string>)[reason] ?? null;
}

function textValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function identifierValue(value: unknown): string {
  return typeof value === "number" && Number.isFinite(value) ? String(value) : textValue(value);
}

function objectTextValues(value: unknown): Record<string, string> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  return Object.fromEntries(Object.entries(value as Record<string, unknown>)
    .flatMap(([key, item]) => textValue(item) ? [[key, conciseText(item)]] : []));
}

function conciseText(value: unknown): string {
  return textValue(value).replaceAll(/\s+/g, " ").slice(0, 240);
}

function readCitations(data: Record<string, unknown>, fallback: Citation[] = []): Citation[] {
  if (Array.isArray(data.citations)) return data.citations.filter((value): value is Citation => typeof value === "object" && value !== null);
  if (typeof data.citation === "object" && data.citation !== null) return [...fallback, data.citation as Citation];
  return fallback;
}

export interface RunStreamConnection { stop(): void; done: Promise<void>; }
export interface RunStreamOptions { accessToken: string; lastEventId?: string | null; fetchImpl?: typeof fetch; onEvent: (event: RunStreamEvent) => void; onError: (message: string) => void; }

/** Fetch-based SSE reader. A reconnect keeps Last-Event-ID so the server replays the durable journal. */
export function connectRunStream(url: string, options: RunStreamOptions): RunStreamConnection {
  const controller = new AbortController();
  const fetchImpl = options.fetchImpl ?? fetch;
  const done = (async () => {
    let lastEventId = options.lastEventId ?? null;
    for (let attempts = 0; attempts < 3 && !controller.signal.aborted; attempts += 1) {
      try {
        const response = await fetchImpl(url, { headers: { Authorization: `Bearer ${options.accessToken}`, Accept: "text/event-stream", ...(lastEventId ? { "Last-Event-ID": lastEventId } : {}) }, signal: controller.signal });
        if (!response.ok || !response.body) throw new Error(`流式连接失败（${response.status}）`);
        for await (const event of parseSse(response.body)) { lastEventId = event.id || lastEventId; options.onEvent(event); }
        return;
      } catch (error) {
        if (controller.signal.aborted) return;
        if (attempts === 2) options.onError(error instanceof Error ? error.message : "流式连接失败");
      }
    }
  })();
  return { stop: () => controller.abort(), done };
}

async function* parseSse(body: ReadableStream<Uint8Array>): AsyncGenerator<RunStreamEvent> {
  const reader = body.getReader(); const decoder = new TextDecoder(); let buffer = "";
  while (true) {
    const next = await reader.read();
    if (next.done) break;
    buffer += decoder.decode(next.value, { stream: true });
    let boundary: number;
    while ((boundary = buffer.indexOf("\n\n")) >= 0) {
      const frame = buffer.slice(0, boundary); buffer = buffer.slice(boundary + 2);
      const values = Object.fromEntries(frame.split("\n").filter(Boolean).map(line => { const index = line.indexOf(":"); return [line.slice(0, index), line.slice(index + 1).trimStart()]; }));
      if (values.data) yield { id: values.id ?? "", type: values.event ?? "message", data: JSON.parse(values.data) as Record<string, unknown> };
    }
  }
}
