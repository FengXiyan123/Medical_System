import type { AuthSession } from "../auth/session";

export interface CursorPage<T> { items: T[]; nextCursor?: string | null; }
export interface TraceFilters { userId?: string; mode?: string; status?: string; createdFrom?: string; createdTo?: string; cursor?: string; }
export interface TraceSummary {
  id: string; traceId: string; userId: string; username?: string | null; conversationId?: string | null;
  mode: string; status: string; startedAt: string; completedAt?: string | null; durationMs?: number | null;
  firstVisibleTokenMs?: number | null; model?: string | null; inputTokens?: number | null; outputTokens?: number | null;
  unknownUsageCount?: number; errorCode?: string | null;
}
export interface TraceSpan { id: string; parentId?: string | null; name: string; status: string; startedAt?: string | null; endedAt?: string | null; durationMs?: number | null; attemptNo?: number | null; inputSummary?: string | null; outputSummary?: string | null; error?: string | null; }
export interface KnowledgeStage { stage: "RETRIEVED" | "RERANKED" | "CONTEXT_INCLUDED" | "CITED"; chunkId: string; documentName?: string | null; generationId?: string | null; snapshot?: string | null; score?: number | null; }
export interface InvocationUsage { invocationId: string; attemptNo: number; purpose: string; model?: string | null; inputTokens?: number | null; outputTokens?: number | null; totalTokens?: number | null; usageSource: "PROVIDER_REPORTED" | "ESTIMATED" | "UNKNOWN"; cost?: number | null; status?: string | null; }
export interface TraceDetail { run: TraceSummary; path: string[]; spans: TraceSpan[]; knowledgeStages: KnowledgeStage[]; invocations: InvocationUsage[]; error?: string | null; }
export interface UsageFilters { userId?: string; model?: string; mode?: string; purpose?: string; from?: string; to?: string; }
export interface UsageRow { date?: string | null; userId?: string | null; username?: string | null; model?: string | null; mode?: string | null; purpose?: string | null; requestCount: number; successCount: number; inputTokens?: number | null; outputTokens?: number | null; unknownUsageCount: number; estimatedCost?: number | null; currency?: string | null; }
export interface UsageReport { items: UsageRow[]; syncedAt: string | null; }
export interface AdminUser { id: string; username: string; displayName?: string; role: "USER" | "ADMIN"; active: boolean; authVersion?: number; }
export interface AuditEvent { id: string; createdAt: string; actorId?: string | null; action: string; targetType?: string | null; targetId?: string | null; traceId?: string | null; redactedDiff?: string | null; }
export interface ModelProfile { id: string; name: string; provider: string; model: string; region?: string | null; endpoint?: string | null; credentialRef?: string | null; enabled: boolean; }
export interface PromptVersion { id: string; name: string; version: number; template: string; hash: string; active: boolean; createdAt?: string | null; }
export interface ExecutionPolicy { id: string; type: string; version: number; configuration: string; active: boolean; createdAt?: string | null; }
export interface KnowledgeBase { id: string; name: string; description: string; status: "DRAFT" | "PUBLISHED" | "ARCHIVED"; accessScope: "ALL_AUTHENTICATED" | "ASSIGNED_USERS"; }
export interface KnowledgeDocument { id: string; knowledgeBaseId: string; originalFilename: string; mediaType: string; status: "UPLOADED" | "PROCESSING" | "READY" | "FAILED" | "ARCHIVED"; latestVersion: number; updatedAt: string; }
export interface KnowledgeGeneration { id: string; documentId: string; generationNo: number; buildStatus: "DRAFT" | "INDEXING" | "BUILD_READY" | "BUILD_FAILED"; publicationStatus: "DRAFT" | "ACTIVE" | "RETIRED"; editRevision: number; chunkCount: number; manifestHash?: string | null; }
export interface IngestionStatus { taskId?: string | null; status: "NOT_QUEUED" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "INTERRUPTED"; stage?: "PARSE" | "CHUNK" | "EMBED" | null; progress: number; errorCode?: string | null; outboxDispatched: boolean; deliveryAttempts: number; deliveryError?: string | null; updatedAt?: string | null; }
export interface AdminChunk { id: string; ordinal: number; content: string; enabled: boolean; manuallyEdited: boolean; pageStart?: number | null; pageEnd?: number | null; sectionPath?: string | null; }
export interface AdminChunkPage { chunks: AdminChunk[]; total: number; editRevision: number; generationState: string; }
export interface GenerationWorkspace { id: string; state: string; editRevision: number; chunks: AdminChunk[]; }

export class AdminApi {
  constructor(private readonly session: AuthSession, private readonly baseUrl = "/api") {}
  listTraces(filters: TraceFilters): Promise<CursorPage<TraceSummary>> { return this.get("/admin/runs", filters); }
  trace(id: string): Promise<TraceDetail> { return this.get(`/admin/runs/${encodeURIComponent(id)}/trace`); }
  usage(filters: UsageFilters): Promise<UsageReport> { return this.get("/admin/usage", filters); }
  listUsers(): Promise<AdminUser[]> { return this.get("/admin/users"); }
  createUser(payload: { username: string; displayName: string; password: string; role: "USER" | "ADMIN" }): Promise<AdminUser> { return this.request("/admin/users", { method: "POST", body: payload }); }
  setUserEnabled(id: string, active: boolean): Promise<void> { return this.request(`/admin/users/${encodeURIComponent(id)}/status`, { method: "POST", body: { active } }); }
  resetPassword(id: string, password: string): Promise<void> { return this.request(`/admin/users/${encodeURIComponent(id)}/reset-password`, { method: "POST", body: { password } }); }
  audit(filters: { action?: string; actorId?: string; from?: string; to?: string; limit?: string }): Promise<AuditEvent[]> { return this.get("/admin/audit-events", filters); }
  modelProfiles(): Promise<ModelProfile[]> { return this.get("/admin/model-profiles"); }
  prompts(): Promise<PromptVersion[]> { return this.get("/admin/prompts"); }
  policies(): Promise<ExecutionPolicy[]> { return this.get("/admin/policies"); }
  listKnowledgeBases(): Promise<KnowledgeBase[]> { return this.get("/admin/knowledge-bases"); }
  createKnowledgeBase(payload: { name: string; description: string; accessScope: "ALL_AUTHENTICATED"; assignedUserIds: string[] }): Promise<KnowledgeBase> { return this.request("/admin/knowledge-bases", { method: "POST", body: payload }); }
  setKnowledgeBaseStatus(id: string, status: KnowledgeBase["status"]): Promise<KnowledgeBase> { return this.request(`/admin/knowledge-bases/${encodeURIComponent(id)}/status`, { method: "POST", body: { status } }); }
  listDocuments(knowledgeBaseId: string): Promise<KnowledgeDocument[]> { return this.get(`/admin/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`); }
  uploadDocument(knowledgeBaseId: string, file: File): Promise<KnowledgeDocument> { const body = new FormData(); body.append("file", file); return this.request(`/admin/knowledge-bases/${encodeURIComponent(knowledgeBaseId)}/documents`, { method: "POST", body }); }
  listGenerations(documentId: string): Promise<KnowledgeGeneration[]> { return this.get(`/admin/documents/${encodeURIComponent(documentId)}/generations`); }
  ingestionStatus(documentId: string): Promise<IngestionStatus> { return this.get(`/admin/documents/${encodeURIComponent(documentId)}/ingestion`); }
  executeIngestion(documentId: string): Promise<{ status: string; documentId: string }> { return this.request(`/admin/documents/${encodeURIComponent(documentId)}/ingestion:execute`, { method: "POST" }); }
  listChunks(generationId: string): Promise<AdminChunkPage> { return this.get(`/admin/generations/${encodeURIComponent(generationId)}/chunks`); }
  updateChunk(generationId: string, chunkId: string, payload: { content?: string; enabled?: boolean; expectedEditRevision: number }): Promise<GenerationWorkspace> { return this.request(`/admin/generations/${encodeURIComponent(generationId)}/chunks/${encodeURIComponent(chunkId)}`, { method: "PATCH", body: payload }); }
  indexGeneration(generationId: string): Promise<KnowledgeGeneration> { return this.request(`/admin/generations/${encodeURIComponent(generationId)}/index`, { method: "POST" }); }
  publishGeneration(documentId: string, generationId: string): Promise<void> { return this.request(`/admin/documents/${encodeURIComponent(documentId)}/publish`, { method: "POST", body: { generationId } }); }

  private get<T>(path: string, parameters: object = {}): Promise<T> {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(parameters)) if (typeof value === "string" && value) query.set(key, value);
    return this.request(`${path}${query.size ? `?${query.toString()}` : ""}`);
  }

  private async request<T>(path: string, options: { method?: string; body?: object | FormData } = {}): Promise<T> {
    const isForm = options.body instanceof FormData;
    const response = await fetch(`${this.baseUrl}${path}`, {
      method: options.method ?? "GET",
      headers: { Authorization: `Bearer ${this.session.accessToken}`, ...(options.body && !isForm ? { "Content-Type": "application/json" } : {}) },
      body: isForm ? options.body as FormData : options.body ? JSON.stringify(options.body) : undefined
    });
    if (response.status === 204) return undefined as T;
    const raw = await response.text();
    let payload: T & { message?: string } = {} as T & { message?: string };
    if (raw) {
      try {
        payload = JSON.parse(raw) as T & { message?: string };
      } catch {
        if (!response.ok) throw new Error(`管理请求失败（HTTP ${response.status}）`);
        throw new Error("管理服务返回了无法识别的数据");
      }
    }
    if (!response.ok) throw new Error(payload.message?.trim() || `管理请求失败（HTTP ${response.status}）`);
    return payload;
  }
}
