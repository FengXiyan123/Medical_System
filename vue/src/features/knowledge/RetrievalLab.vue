<script setup lang="ts">
import { ref } from "vue";
import type { AuthSession } from "../auth/session";
import type { AccessibleKnowledgeBase } from "../chat/api";
import { RetrievalApi, type RetrievalTestResult } from "./retrievalApi";
import { createRetrievalLabState } from "./retrievalLab";

const props = withDefaults(defineProps<{ session: AuthSession; knowledgeBases?: AccessibleKnowledgeBase[] }>(), { knowledgeBases: () => [] });
const lab = createRetrievalLabState(); const query = ref(""); const selectedKnowledgeBaseIds = ref<string[]>([]); const result = ref<RetrievalTestResult | null>(null);
async function execute() {
  if (!query.value.trim()) return;
  lab.start(); result.value = null;
  try { const response = await new RetrievalApi(props.session).test(query.value, selectedKnowledgeBaseIds.value); result.value = response; if (!response.recalled.length) lab.empty(); else lab.ready({ rerankerStatus: response.rerankerStatus }); }
  catch (cause) { const text = cause instanceof Error ? cause.message : "检索测试失败"; if (/权限|403|FORBIDDEN/i.test(text)) lab.forbidden(text); else lab.failed(text); }
}
</script>
<template><section class="retrieval-lab" aria-label="检索调试台"><header><h2>检索调试台</h2><p>只在当前管理员有权访问的知识范围内执行，不生成回答。</p></header><form @submit.prevent="execute"><label>测试问题<textarea v-model="query" maxlength="10000" placeholder="输入检索问题" /></label><fieldset v-if="knowledgeBases?.length"><legend>限定知识库（留空为全部授权库）</legend><label v-for="kb in knowledgeBases" :key="kb.id"><input v-model="selectedKnowledgeBaseIds" type="checkbox" :value="kb.id" />{{ kb.name }}</label></fieldset><button type="submit" :disabled="lab.status.value === 'LOADING'">{{ lab.status.value === 'LOADING' ? '检索中…' : '运行检索测试' }}</button><button v-if="lab.canRetry.value" type="button" @click="execute">重试</button></form><p v-if="lab.status.value === 'FORBIDDEN'" class="error">{{ lab.message.value }}</p><p v-else-if="lab.status.value === 'FAILED'" class="error">{{ lab.message.value }}</p><p v-else-if="lab.status.value === 'EMPTY'" class="empty">{{ lab.emptyMessage.value }}</p><template v-else-if="result"><p><strong>改写查询：</strong>{{ result.rewrittenQuery }}</p><p v-if="result.rerankerStatus === 'NOT_CONFIGURED'" class="empty">{{ lab.rerankerMessage.value }}</p><p>测试用量用途：{{ result.usagePurpose }}</p><section v-for="stage in [{ name: '召回', items: result.recalled }, { name: '重排', items: result.reranked }, { name: '上下文', items: result.context }]" :key="stage.name"><h3>{{ stage.name }}（{{ stage.items.length }}）</h3><p v-if="!stage.items.length" class="empty">该阶段没有切块。</p><ol v-else><li v-for="item in stage.items" :key="item.chunkId"><strong>{{ item.chunkId }}</strong> <span v-if="'rawScore' in item">{{ item.scoreType }}：{{ item.rawScore }}</span><p>{{ 'textSnapshot' in item ? item.textSnapshot : item.content }}</p></li></ol></section></template></section></template>
<style scoped>.retrieval-lab{display:grid;gap:1rem}.retrieval-lab form,.retrieval-lab label,.retrieval-lab fieldset{display:grid;gap:.5rem}.retrieval-lab textarea{min-height:5rem;padding:.6rem;font:inherit}.retrieval-lab button{width:max-content;padding:.45rem .7rem}.error{color:#b42318}.empty{color:#55706f}.retrieval-lab p{margin:.2rem 0}.retrieval-lab li p{white-space:pre-wrap}</style>
