<script setup lang="ts">
import { computed, ref, watch } from "vue";

import type { GenerationChunk } from "./chunkWorkspace";

const props = defineProps<{
  chunk: GenerationChunk | null;
  editRevision: number;
  readOnly: boolean;
}>();

const emit = defineEmits<{
  update: [payload: { chunkId: string; content: string; expectedEditRevision: number }];
  toggle: [payload: { chunkId: string; enabled: boolean; expectedEditRevision: number }];
  split: [payload: { chunkId: string; atCharacter: number; expectedEditRevision: number }];
}>();

const content = ref("");
watch(() => props.chunk, (chunk) => { content.value = chunk?.content ?? ""; }, { immediate: true });
const canSplit = computed(() => !!props.chunk && content.value.trim().length > 1 && !props.readOnly);

function save() {
  if (!props.chunk || props.readOnly) return;
  emit("update", { chunkId: props.chunk.id, content: content.value, expectedEditRevision: props.editRevision });
}

function toggle() {
  if (!props.chunk || props.readOnly) return;
  emit("toggle", { chunkId: props.chunk.id, enabled: !props.chunk.enabled, expectedEditRevision: props.editRevision });
}

function split() {
  if (!props.chunk || !canSplit.value) return;
  emit("split", { chunkId: props.chunk.id, atCharacter: Math.floor(content.value.length / 2), expectedEditRevision: props.editRevision });
}
</script>

<template>
  <aside v-if="chunk" class="chunk-detail">
    <h3>切块 {{ chunk.ordinal + 1 }}</h3>
    <p>页码：{{ chunk.pageStart ?? "未提供" }}<span v-if="chunk.pageEnd">–{{ chunk.pageEnd }}</span></p>
    <p v-if="chunk.sectionPath">章节：{{ chunk.sectionPath }}</p>
    <p v-if="chunk.manuallyEdited" class="edited">已人工整理</p>
    <textarea v-model="content" :readonly="readOnly" aria-label="切块正文" />
    <div class="actions">
      <button type="button" :disabled="readOnly" @click="save">保存正文</button>
      <button type="button" :disabled="readOnly" @click="toggle">{{ chunk.enabled ? "禁用" : "启用" }}</button>
      <button type="button" :disabled="!canSplit" @click="split">从中间拆分</button>
    </div>
  </aside>
  <aside v-else class="chunk-detail">请选择一个切块。</aside>
</template>

<style scoped>
.chunk-detail { display: grid; gap: .65rem; padding: 1rem; border-left: 1px solid #d9e9e5; }
h3, p { margin: 0; }
textarea { min-height: 16rem; padding: .65rem; resize: vertical; }
.actions { display: flex; flex-wrap: wrap; gap: .5rem; }
.edited { color: #277871; font-weight: 700; }
</style>
