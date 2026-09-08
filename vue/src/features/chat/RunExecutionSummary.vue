<script setup lang="ts">
import type { ExecutionStep, RouteSelection } from "./runStream";

defineProps<{
  route: RouteSelection | null;
  steps: ExecutionStep[];
  budgetTermination: string | null;
  summary: Record<string, unknown> | null;
}>();

const statusText = { RUNNING: "进行中", COMPLETED: "已完成", FAILED: "失败" } as const;
</script>

<template>
  <section class="execution-summary" aria-label="本次执行过程">
    <h4>本次执行过程</h4>
    <p v-if="budgetTermination" class="budget-stop" role="status">{{ budgetTermination }}</p>

    <section v-if="route" class="route-selection" aria-label="自动知识路由">
      <h5>自动知识路由</h5>
      <p v-if="route.reason">{{ route.reason }}</p>
      <p v-if="route.fallbackUsed">初次检索无命中，已在当前授权知识范围内进行一次兜底检索。</p>
      <dl>
        <div><dt>候选知识库</dt><dd>{{ route.candidates.length || "无" }}</dd></div>
        <div><dt>最终选择</dt><dd>{{ route.selectedKnowledgeBaseIds.length || "无" }}</dd></div>
      </dl>
      <ul v-if="route.candidates.length" class="candidates">
        <li v-for="candidate in route.candidates" :key="candidate.id" :class="{ selected: route.selectedKnowledgeBaseIds.includes(candidate.id) }">
          <span>{{ candidate.name }}</span><small v-if="candidate.version">版本 {{ candidate.version }}</small>
          <small v-if="route.selectionReasons[candidate.id]">{{ route.selectionReasons[candidate.id] }}</small>
          <em v-if="route.selectedKnowledgeBaseIds.includes(candidate.id)">已选</em>
        </li>
      </ul>
    </section>

    <ol v-if="steps.length" class="execution-steps">
      <li v-for="step in steps" :key="step.id" :class="['step', step.status.toLowerCase()]">
        <span class="step-kind">{{ step.kind === "TOOL" ? "工具" : step.kind === "ROUTE" ? "路由" : "节点" }}</span>
        <strong>{{ step.label }}</strong>
        <span>{{ statusText[step.status] }}</span>
        <p v-if="step.detail">{{ step.detail }}</p>
      </li>
    </ol>
    <p v-else-if="summary" class="empty-process">运行摘要已保存，尚未提供可展示的节点事件。</p>
  </section>
</template>

<style scoped>
.execution-summary { display:grid; gap:.65rem; padding:.7rem; border:1px solid #cfe2de; border-radius:.6rem; background:#fbfefd; color:#2e5350; }
h4, h5, p { margin:0; } h4 { font-size:.9rem; } h5 { font-size:.82rem; } p { font-size:.8rem; line-height:1.45; }
.budget-stop { padding:.45rem .55rem; border-radius:.4rem; color:#8a3b00; background:#fff2db; }
.route-selection { display:grid; gap:.4rem; padding:.6rem; border-left:3px solid #4a9187; background:#f1f8f6; }
dl { display:flex; gap:1rem; margin:0; font-size:.76rem; } dl div { display:flex; gap:.25rem; } dt { color:#55706f; } dd { margin:0; }
.candidates, .execution-steps { display:grid; gap:.4rem; margin:0; padding:0; list-style:none; }
.candidates li { display:flex; flex-wrap:wrap; gap:.45rem; align-items:center; padding:.4rem .55rem; border:1px solid #dbe9e6; border-radius:.4rem; background:white; font-size:.78rem; }
.candidates li.selected { border-color:#65a99f; background:#e7f5f1; } small { color:#607977; } em { color:#17675f; font-style:normal; font-weight:700; }
.step { display:flex; flex-wrap:wrap; gap:.45rem; align-items:center; padding:.45rem .55rem; border-radius:.4rem; background:#f4f8f7; font-size:.78rem; }.step.failed { background:#fff1f0; color:#8e2e25; }.step p { width:100%; color:inherit; }.step-kind { min-width:2.2rem; color:#55706f; }.step.running strong::after { content:" …"; }
.empty-process { color:#55706f; }
</style>
