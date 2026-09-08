import { computed, ref } from "vue";
import type { ComputedRef, Ref } from "vue";

import type { ExecutionModeId } from "./modes";

export interface RunRequestModeFields {
  mode: ExecutionModeId;
  knowledge_base_ids?: string[];
}

export interface ChatModeState {
  mode: Ref<ExecutionModeId>;
  selectedKnowledgeBaseIds: Ref<string[]>;
  request: ComputedRef<RunRequestModeFields>;
  selectMode: (mode: ExecutionModeId) => void;
  setKnowledgeBaseIds: (knowledgeBaseIds: string[]) => void;
}

export function createChatModeState(): ChatModeState {
  const mode = ref<ExecutionModeId>("AUTO_KB");
  const selectedKnowledgeBaseIds = ref<string[]>([]);

  const selectMode = (nextMode: ExecutionModeId): void => {
    mode.value = nextMode;
    if (nextMode !== "MANUAL_KB") {
      selectedKnowledgeBaseIds.value = [];
    }
  };

  const setKnowledgeBaseIds = (knowledgeBaseIds: string[]): void => {
    selectedKnowledgeBaseIds.value = [...new Set(knowledgeBaseIds)];
  };

  const request = computed<RunRequestModeFields>(() => {
    if (mode.value === "MANUAL_KB") {
      return {
        mode: mode.value,
        knowledge_base_ids: selectedKnowledgeBaseIds.value
      };
    }
    return { mode: mode.value };
  });

  return { mode, selectedKnowledgeBaseIds, request, selectMode, setKnowledgeBaseIds };
}
