import { computed, ref } from "vue";

export type GenerationState = "DRAFT" | "INDEXING" | "BUILD_READY" | "ACTIVE" | "RETIRED";

export interface GenerationChunk {
  id: string;
  ordinal: number;
  content: string;
  enabled: boolean;
  manuallyEdited: boolean;
  pageStart: number | null;
  pageEnd?: number | null;
  sectionPath?: string | null;
}

export interface GenerationView {
  state: GenerationState;
  editRevision: number;
  chunks: GenerationChunk[];
}

/** Presentation state for the three-pane administrator chunk workspace. */
export function createChunkWorkspaceState(initial: GenerationView) {
  const state = ref<GenerationState>(initial.state);
  const editRevision = ref(initial.editRevision);
  const chunks = ref<GenerationChunk[]>([...initial.chunks]);
  const selectedChunkId = ref<string | null>(initial.chunks[0]?.id ?? null);
  const needsRefresh = ref(false);
  const isReadOnly = computed(() => state.value !== "DRAFT");
  const selectedChunk = computed(() => chunks.value.find((chunk) => chunk.id === selectedChunkId.value) ?? null);
  const visibleChunks = computed(() => [...chunks.value].sort((left, right) => left.ordinal - right.ordinal));
  const draftNotice = computed(() => {
    if (isReadOnly.value) return "此代际已冻结或发布，只读。修改请创建新的草稿代际。";
    if (needsRefresh.value) return "其他管理员已更新切块，请刷新并比对差异后再提交。";
    return "草稿变更尚未生效；完成索引并发布后才会用于新检索。";
  });

  function selectChunk(chunkId: string) {
    selectedChunkId.value = chunkId;
  }

  function applyServerGeneration(next: GenerationView) {
    state.value = next.state;
    editRevision.value = next.editRevision;
    chunks.value = [...next.chunks];
    if (!chunks.value.some((chunk) => chunk.id === selectedChunkId.value)) {
      selectedChunkId.value = chunks.value[0]?.id ?? null;
    }
    needsRefresh.value = false;
  }

  function applyEditConflict() {
    needsRefresh.value = true;
  }

  return {
    state,
    editRevision,
    chunks,
    selectedChunkId,
    selectedChunk,
    visibleChunks,
    isReadOnly,
    needsRefresh,
    draftNotice,
    selectChunk,
    applyServerGeneration,
    applyEditConflict
  };
}
