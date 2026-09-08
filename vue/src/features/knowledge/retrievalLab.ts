import { computed, ref } from "vue";

export type RetrievalLabStatus = "IDLE" | "LOADING" | "EMPTY" | "FORBIDDEN" | "FAILED" | "READY";

/** Explicit UI states prevent a missing reranker score from being displayed as zero. */
export function createRetrievalLabState() {
  const status = ref<RetrievalLabStatus>("IDLE");
  const message = ref("");
  const rerankerStatus = ref<"CONFIGURED" | "NOT_CONFIGURED" | null>(null);
  const canRetry = computed(() => status.value === "FAILED");
  const emptyMessage = computed(() => status.value === "EMPTY" ? "没有召回符合当前授权范围的知识切块。" : "");
  const rerankerMessage = computed(() => rerankerStatus.value === "NOT_CONFIGURED" ? "重排未启用，列表按混合召回排序。" : "");
  return {
    status, message, rerankerStatus, canRetry, emptyMessage, rerankerMessage,
    start: () => { status.value = "LOADING"; message.value = ""; },
    empty: () => { status.value = "EMPTY"; },
    forbidden: (value: string) => { status.value = "FORBIDDEN"; message.value = value; },
    failed: (value: string) => { status.value = "FAILED"; message.value = value; },
    ready: (result: { rerankerStatus: "CONFIGURED" | "NOT_CONFIGURED" }) => { status.value = "READY"; rerankerStatus.value = result.rerankerStatus; }
  };
}
