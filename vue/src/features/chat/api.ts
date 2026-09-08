import type { AuthSession } from "../auth/session";
import type { ExecutionModeId } from "./modes";
import type { ExecutionStep, RouteSelection } from "./runStream";

export interface Conversation { id: string; title: string; status: "ACTIVE" | "ARCHIVED"; createdAt: string; updatedAt: string; }
/** The user-visible directory; server-side scope still remains authoritative at run admission. */
export interface AccessibleKnowledgeBase { id: string; name: string; description: string | null; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; }
export interface ChatMessage {
  id: string; runId: string | null; role: "USER" | "ASSISTANT" | "SYSTEM"; content: string;
  citationManifest: string | null; createdAt: string; citations?: unknown[]; summary?: Record<string, unknown> | null;
  execution?: { route: RouteSelection | null; steps: ExecutionStep[]; budgetTermination: string | null };
  streaming?: boolean;
}
export interface RunAcceptance { run: { id: string; status: string }; reused: boolean; }

export class ChatApi {
  constructor(private readonly session: AuthSession, private readonly baseUrl = "/api") {}
  async listConversations(): Promise<Conversation[]> { return (await this.request<{ items: Conversation[] }>("/v1/conversations")).items; }
  listAccessibleKnowledgeBases(): Promise<AccessibleKnowledgeBase[]> { return this.request("/v1/knowledge-bases"); }
  createConversation(title: string): Promise<Conversation> { return this.request("/v1/conversations", { method: "POST", body: { title } }); }
  deleteConversation(conversationId: string): Promise<void> { return this.request(`/v1/conversations/${conversationId}`, { method: "DELETE" }); }
  async messages(conversationId: string): Promise<ChatMessage[]> { return (await this.request<{ items: ChatMessage[] }>(`/v1/conversations/${conversationId}/messages`)).items; }
  submitRun(conversationId: string, question: string, mode: ExecutionModeId, selectedKnowledgeBaseIds: string[]): Promise<RunAcceptance> {
    const safeSelection = mode === "MANUAL_KB" ? [...new Set(selectedKnowledgeBaseIds)] : [];
    return this.request(`/v1/conversations/${conversationId}/runs`, {
      method: "POST",
      headers: { "Idempotency-Key": crypto.randomUUID() },
      body: { question, mode, selectedKnowledgeBaseIds: safeSelection }
    });
  }
  cancelRun(conversationId: string, runId: string): Promise<void> { return this.request(`/v1/conversations/${conversationId}/runs/${runId}`, { method: "DELETE" }); }
  submitFeedback(answerId: string, rating: "UP" | "DOWN", reason?: string): Promise<void> {
    return this.request(`/v1/answers/${answerId}/feedback`, { method: "PUT", body: { rating, reason: reason?.trim() || null } });
  }
  streamUrl(conversationId: string, runId: string): string { return `${this.baseUrl}/v1/conversations/${conversationId}/runs/${runId}/events`; }
  private async request<T>(path: string, options: { method?: string; headers?: Record<string, string>; body?: object } = {}): Promise<T> {
    const response = await fetch(`${this.baseUrl}${path}`, { method: options.method ?? "GET", headers: { Authorization: `Bearer ${this.session.accessToken}`, ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers }, body: options.body ? JSON.stringify(options.body) : undefined });
    if (response.status === 204) return undefined as T;
    const result = await response.json() as T & { message?: string };
    if (!response.ok) throw new Error(result.message ?? "请求失败");
    return result;
  }
}
