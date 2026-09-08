<script setup lang="ts">
import { ref } from "vue";

import ChunkDetail from "./ChunkDetail.vue";
import { createChunkWorkspaceState, type GenerationView } from "./chunkWorkspace";

const props = defineProps<{ generation: GenerationView }>();
const emit = defineEmits<{
  update: [payload: { chunkId: string; content: string; expectedEditRevision: number }];
  toggle: [payload: { chunkId: string; enabled: boolean; expectedEditRevision: number }];
  split: [payload: { chunkId: string; atCharacter: number; expectedEditRevision: number }];
  merge: [payload: { firstChunkId: string; secondChunkId: string; expectedEditRevision: number }];
  refresh: [];
}>();

const workspace = createChunkWorkspaceState(props.generation);
const query = ref("");

function mergeSelectedWithNext() {
  const current = workspace.selectedChunk.value;
  if (!current || workspace.isReadOnly.value) return;
  const next = workspace.visibleChunks.value[current.ordinal + 1];
  if (next) emit("merge", { firstChunkId: current.id, secondChunkId: next.id, expectedEditRevision: workspace.editRevision.value });
}
</script>

<template>
  <section class="chunk-workspace">
    <header>
      <h2>切块工作台</h2>
      <p :class="{ warning: workspace.needsRefresh.value }">{{ workspace.draftNotice }}</p>
      <button v-if="workspace.needsRefresh.value" type="button" @click="emit('refresh')">刷新差异</button>
    </header>
    <input v-model="query" type="search" placeholder="按正文或章节筛选" aria-label="筛选切块" />
    <div class="panes">
      <nav aria-label="切块列表">
        <button
          v-for="chunk in workspace.visibleChunks.value.filter((item) => item.content.includes(query))"
          :key="chunk.id"
          type="button"
          :class="{ selected: workspace.selectedChunkId.value === chunk.id, disabled: !chunk.enabled }"
          @click="workspace.selectChunk(chunk.id)"
        >
          <strong>#{{ chunk.ordinal + 1 }}</strong><span>第 {{ chunk.pageStart ?? "?" }} 页</span>
          <small>{{ chunk.content.slice(0, 48) }}</small>
        </button>
      </nav>
      <ChunkDetail
        :chunk="workspace.selectedChunk.value"
        :edit-revision="workspace.editRevision.value"
        :read-only="workspace.isReadOnly.value"
        @update="emit('update', $event)"
        @toggle="emit('toggle', $event)"
        @split="emit('split', $event)"
      />
    </div>
    <button type="button" :disabled="workspace.isReadOnly.value || !workspace.selectedChunk.value" @click="mergeSelectedWithNext">与下一块合并</button>
  </section>
</template>

<style scoped>
.chunk-workspace { display: grid; gap: 1rem; padding: 1rem; border: 1px solid #d9e9e5; border-radius: 1rem; background: white; }
header { display: flex; flex-wrap: wrap; align-items: center; gap: .75rem; }
h2, p { margin: 0; }.warning { color: #b54708; }.panes { display: grid; grid-template-columns: minmax(12rem, 1fr) 2fr; min-height: 22rem; border: 1px solid #d9e9e5; }
nav { display: grid; align-content: start; gap: .5rem; padding: .75rem; overflow: auto; } nav button { display: grid; gap: .2rem; padding: .65rem; text-align: left; background: white; border: 1px solid #d9e9e5; border-radius: .5rem; cursor: pointer; }.selected { border-color: #20756d; background: #e8f6f2; }.disabled { opacity: .55; } small { color: #55706f; } @media (max-width: 42rem) { .panes { grid-template-columns: 1fr; } }
</style>
