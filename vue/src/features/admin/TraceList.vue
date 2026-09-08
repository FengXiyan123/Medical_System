<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";

import type { AdminApi, TraceFilters, TraceSummary } from "./api";

const props = defineProps<{ api: AdminApi }>();
const emit = defineEmits<{ select: [trace: TraceSummary] }>();
const filters = reactive<TraceFilters>({});
const items = ref<TraceSummary[]>([]);
const loading = ref(false);
const error = ref("");

async function load() {
  loading.value = true;
  error.value = "";
  try { items.value = (await props.api.listTraces(filters)).items; }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "无法加载调用链"; }
  finally { loading.value = false; }
}

onMounted(load);
</script>

<template>
  <section class="admin-card" aria-labelledby="trace-list-heading">
    <header><div><h2 id="trace-list-heading">调用链</h2><p>按运行状态、执行方式和时间筛选；只显示运行摘要。</p></div><button type="button" :disabled="loading" @click="load">刷新</button></header>
    <form class="filters" @submit.prevent="load">
      <input v-model="filters.userId" aria-label="用户 ID" placeholder="用户 ID" />
      <select v-model="filters.mode" aria-label="执行方式"><option value="">全部方式</option><option value="AUTO_KB">自动知识</option><option value="MANUAL_KB">自选知识</option><option value="AGENT">自由执行</option></select>
      <select v-model="filters.status" aria-label="运行状态"><option value="">全部状态</option><option value="SUCCEEDED">成功</option><option value="FAILED">失败</option><option value="CANCELLED">已取消</option><option value="INTERRUPTED">已中断</option></select>
      <input v-model="filters.createdFrom" aria-label="开始日期" type="date" /><input v-model="filters.createdTo" aria-label="结束日期" type="date" />
      <button type="submit" :disabled="loading">筛选</button>
    </form>
    <p v-if="loading" class="state">正在加载调用链…</p><p v-else-if="error" class="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p>
    <p v-else-if="!items.length" class="state">没有符合条件的调用链。</p>
    <div v-else class="table-wrap"><table><thead><tr><th>运行</th><th>用户</th><th>方式</th><th>状态</th><th>耗时</th><th>Token</th><th></th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td><code>{{ item.traceId }}</code><small>{{ item.startedAt }}</small></td><td>{{ item.username ?? item.userId }}</td><td>{{ item.mode }}</td><td>{{ item.status }}<small v-if="item.errorCode" class="error">{{ item.errorCode }}</small></td><td>{{ item.durationMs ?? "—" }} ms</td><td>{{ item.inputTokens ?? "—" }} / {{ item.outputTokens ?? "—" }}<small v-if="item.unknownUsageCount">未知 {{ item.unknownUsageCount }}</small></td><td><button type="button" @click="emit('select', item)">查看</button></td></tr></tbody></table></div>
  </section>
</template>

<style scoped>
.admin-card { display:grid; gap:1rem; }.admin-card header { display:flex; justify-content:space-between; gap:1rem; align-items:start; }.admin-card h2,.admin-card p { margin:0; }.admin-card header p,.state,small { color:#55706f; font-size:.85rem; }.filters { display:flex; flex-wrap:wrap; gap:.55rem; }.filters input,.filters select { min-height:2.25rem; padding:0 .55rem; border:1px solid #cfe2de; border-radius:.45rem; }.table-wrap { overflow:auto; } table { width:100%; border-collapse:collapse; font-size:.85rem; } th,td { padding:.7rem .5rem; border-bottom:1px solid #e5efed; text-align:left; vertical-align:top; } td small { display:block; margin-top:.2rem; } code { font-size:.75rem; } button { padding:.42rem .7rem; border:1px solid #20756d; border-radius:.4rem; color:#17675f; background:white; cursor:pointer; }.error { color:#b42318; }
</style>
