import type { AuthSession } from "../auth/session";

export interface RetrievalHit { stage: string; rank: number; knowledgeBaseId: string; generationId: string; chunkId: string; rawScore: number; scoreType: string; textSnapshot: string; }
export interface RetrievalContext { citationId: string; knowledgeBaseId: string; generationId: string; chunkId: string; tokenCount: number; content: string; }
export interface RetrievalTestResult { rewrittenQuery: string; recalled: RetrievalHit[]; reranked: RetrievalHit[]; context: RetrievalContext[]; rerankerStatus: "CONFIGURED" | "NOT_CONFIGURED"; rerankedScoreType: string | null; usagePurpose: "RETRIEVAL_TEST"; }

export class RetrievalApi {
  constructor(private readonly session: AuthSession, private readonly baseUrl = "/api") {}
  async test(query: string, knowledgeBaseIds: string[]): Promise<RetrievalTestResult> {
    const response = await fetch(`${this.baseUrl}/admin/retrieval-tests`, { method: "POST", headers: { Authorization: `Bearer ${this.session.accessToken}`, "Content-Type": "application/json" }, body: JSON.stringify({ query, mode: "MANUAL_KB", knowledgeBaseIds: [...new Set(knowledgeBaseIds)] }) });
    const result = await response.json() as RetrievalTestResult & { message?: string; detail?: string };
    if (!response.ok) throw new Error(result.message ?? result.detail ?? `检索测试失败（${response.status}）`);
    return result;
  }
}
