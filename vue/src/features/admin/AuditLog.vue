<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";

import type { AdminApi, AuditEvent } from "./api";
const props = defineProps<{ api: AdminApi }>(); const filters = reactive({ action:"", actorId:"", from:"", to:"" }); const events = ref<AuditEvent[]>([]); const loading = ref(false); const error = ref("");
async function load() { loading.value=true; error.value=""; try { events.value=await props.api.audit(filters); } catch(cause) { error.value=cause instanceof Error ? cause.message : "无法加载操作审计"; } finally { loading.value=false; } }
onMounted(load);
</script>
<template>
  <section class="admin-card" aria-labelledby="audit-heading"><header><div><h2 id="audit-heading">操作审计</h2><p>登录、权限、知识发布、正文查看与配置变更都可追溯。</p></div><button type="button" :disabled="loading" @click="load">刷新</button></header><form class="filters" @submit.prevent="load"><input v-model="filters.action" placeholder="操作类型" aria-label="操作类型"/><input v-model="filters.actorId" placeholder="操作者 ID" aria-label="操作者 ID"/><input v-model="filters.from" type="date" aria-label="开始日期"/><input v-model="filters.to" type="date" aria-label="结束日期"/><button type="submit">筛选</button></form><p v-if="loading" class="state">正在加载审计事件…</p><p v-else-if="error" class="error" role="alert">{{ error }} <button type="button" @click="load">重试</button></p><p v-else-if="!events.length" class="state">没有符合条件的审计事件。</p><div v-else class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>操作</th><th>目标</th><th>变更摘要</th><th>关联 Trace</th></tr></thead><tbody><tr v-for="event in events" :key="event.id"><td>{{ event.createdAt }}</td><td>{{ event.actorId ?? "系统" }}</td><td>{{ event.action }}</td><td>{{ event.targetType ?? "—" }}<small v-if="event.targetId">{{ event.targetId }}</small></td><td><small>{{ event.redactedDiff ?? "—" }}</small></td><td><code>{{ event.traceId ?? "—" }}</code></td></tr></tbody></table></div></section>
</template>
<style scoped>
.admin-card { display:grid; gap:1rem; }.admin-card header { display:flex; justify-content:space-between; gap:1rem; }.admin-card h2,.admin-card p { margin:0; }.admin-card header p,.state,small { color:#55706f; font-size:.85rem; }.filters { display:flex; flex-wrap:wrap; gap:.55rem; }.filters input { min-height:2.25rem; padding:0 .55rem; border:1px solid #cfe2de; border-radius:.45rem; }.table-wrap { overflow:auto; } table { width:100%; border-collapse:collapse; font-size:.84rem; } th,td { padding:.6rem .5rem; border-bottom:1px solid #e5efed; text-align:left; vertical-align:top; } td small { display:block; margin-top:.2rem; } code { font-size:.75rem; } button { padding:.42rem .7rem; border:1px solid #20756d; border-radius:.4rem; color:#17675f; background:white; cursor:pointer; }.error { color:#b42318; }
</style>
