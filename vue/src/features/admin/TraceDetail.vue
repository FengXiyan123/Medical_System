<script setup lang="ts">
import { ref, watch } from "vue";

import type { AdminApi, TraceDetail as TraceDetailModel } from "./api";

const props = defineProps<{ api: AdminApi; runId: string | null }>();
const detail = ref<TraceDetailModel | null>(null);
const loading = ref(false);
const error = ref("");
async function load() {
  if (!props.runId) { detail.value = null; return; }
  loading.value = true; error.value = "";
  try { detail.value = await props.api.trace(props.runId); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "无法加载调用详情"; }
  finally { loading.value = false; }
}
watch(() => props.runId, load, { immediate: true });
</script>

<template>
  <section class="admin-card trace-detail" aria-labelledby="trace-detail-heading">
    <header><div><h2 id="trace-detail-heading">调用详情</h2><p>显示执行证据与摘要，不显示模型隐藏推理。</p></div><button v-if="runId" type="button" :disabled="loading" @click="load">刷新</button></header>
    <p v-if="!runId" class="state">从列表选择一条调用链查看路径与证据。</p><p v-else-if="loading" class="state">正在加载详情…</p><p v-else-if="error" class="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <template v-else-if="detail"><p v-if="detail.error" class="error">{{ detail.error }}</p><section><h3>执行路径</h3><ol class="path"><li v-for="node in detail.path" :key="node">{{ node }}</li></ol></section><section><h3>节点瀑布</h3><ol class="waterfall"><li v-for="span in detail.spans" :key="span.id"><strong>{{ span.name }}</strong><span>{{ span.status }}</span><span>{{ span.durationMs ?? "—" }} ms</span><small v-if="span.attemptNo">尝试 {{ span.attemptNo }}</small><p v-if="span.error" class="error">{{ span.error }}</p><p v-else-if="span.outputSummary">{{ span.outputSummary }}</p></li></ol></section><section><h3>知识证据</h3><p v-if="!detail.knowledgeStages.length" class="state">本次没有知识证据节点。</p><ul v-else class="evidence"><li v-for="stage in detail.knowledgeStages" :key="`${stage.stage}:${stage.chunkId}`"><strong>{{ stage.stage }}</strong><span>{{ stage.documentName ?? stage.chunkId }}</span><small>版本 {{ stage.generationId ?? "—" }} · 分数 {{ stage.score ?? "—" }}</small><p v-if="stage.snapshot">{{ stage.snapshot }}</p></li></ul></section><section><h3>模型调用与用量</h3><table><thead><tr><th>用途</th><th>尝试</th><th>Token</th><th>来源</th><th>费用</th></tr></thead><tbody><tr v-for="call in detail.invocations" :key="`${call.invocationId}:${call.attemptNo}`"><td>{{ call.purpose }}<small>{{ call.model ?? "—" }}</small></td><td>{{ call.attemptNo }}</td><td>{{ call.inputTokens ?? "—" }} / {{ call.outputTokens ?? "—" }}</td><td>{{ call.usageSource }}</td><td>{{ call.cost ?? "未定价" }}</td></tr></tbody></table></section></template>
  </section>
</template>

<style scoped>
.admin-card { display:grid; gap:1rem; }.admin-card header { display:flex; justify-content:space-between; gap:1rem; }.admin-card h2,.admin-card h3,.admin-card p { margin:0; }.admin-card h3 { font-size:.92rem; }.state,small { color:#55706f; font-size:.84rem; }.path,.waterfall,.evidence { display:grid; gap:.45rem; margin:.45rem 0 0; padding:0; list-style:none; }.path { grid-template-columns:repeat(auto-fit,minmax(7rem,1fr)); }.path li { padding:.45rem; border-radius:.4rem; text-align:center; color:#17675f; background:#e8f6f2; }.waterfall li,.evidence li { display:flex; flex-wrap:wrap; gap:.55rem; align-items:center; padding:.55rem; border-left:3px solid #6da69e; background:#f8fbfa; }.waterfall p,.evidence p { width:100%; font-size:.82rem; }.table-wrap { overflow:auto; } table { width:100%; border-collapse:collapse; font-size:.83rem; } th,td { padding:.55rem; border-bottom:1px solid #e5efed; text-align:left; }.error { color:#b42318; } button { padding:.42rem .7rem; border:1px solid #20756d; border-radius:.4rem; color:#17675f; background:white; cursor:pointer; }
</style>
