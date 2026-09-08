<script setup lang="ts">
import { computed, ref } from "vue";
import { DataAnalysis, Document, Histogram, MagicStick, Operation, SetUp, UserFilled } from "@element-plus/icons-vue";

import type { AuthSession } from "../auth/session";
import AuditLog from "./AuditLog.vue";
import { AdminApi, type TraceSummary } from "./api";
import { adminViews, createAdminState, type AdminView } from "./adminState";
import ModelPolicy from "./ModelPolicy.vue";
import TraceDetail from "./TraceDetail.vue";
import TraceList from "./TraceList.vue";
import UsageDashboard from "./UsageDashboard.vue";
import UserManagement from "./UserManagement.vue";
import RetrievalLab from "../knowledge/RetrievalLab.vue";
import KnowledgeManagement from "./KnowledgeManagement.vue";

const props = defineProps<{ session: AuthSession }>();
const state = createAdminState();
state.open(props.session.user.role);
const api = computed(() => new AdminApi(props.session));
const selectedTrace = ref<TraceSummary | null>(null);
const viewIcon: Record<AdminView, typeof Document> = {
  KNOWLEDGE: Document,
  TRACES: Operation,
  USAGE: Histogram,
  USERS: UserFilled,
  AUDIT: DataAnalysis,
  POLICIES: SetUp
};
const viewLead: Record<AdminView, string> = {
  KNOWLEDGE: "从上传资料到发布版本，完成知识库的可控上线。",
  TRACES: "查看每一次 Agent 运行的路径、证据和模型调用。",
  USAGE: "按用户、模型和用途核对请求与 Token 用量。",
  USERS: "管理教学演示账户与访问状态。",
  AUDIT: "追溯管理员操作与关键对象变更。",
  POLICIES: "查看当前模型档案、提示词和执行策略。"
};
function selectTrace(trace: TraceSummary) { selectedTrace.value = trace; }
</script>

<template>
  <section v-if="state.visible.value" class="admin-workspace" aria-label="管理员后台">
    <header class="admin-hero">
      <div><p>ADMINISTRATOR CONSOLE</p><h1>运营与可观测性</h1><span>知识库发布、执行链路和权限管理都在这里完成。所有动作都会通过服务端授权并写入审计记录。</span></div>
      <div class="hero-status"><el-icon><MagicStick /></el-icon><span>管理员模式</span><small>已验证服务端权限</small></div>
    </header>

    <div class="admin-layout">
      <aside class="admin-sidebar">
        <p class="nav-label">工作模块</p>
        <nav aria-label="管理功能">
          <button v-for="view in adminViews" :key="view.id" type="button" :class="{ selected: state.activeView.value === view.id }" @click="state.select(view.id)">
            <el-icon><component :is="viewIcon[view.id]" /></el-icon><span>{{ view.label }}</span>
          </button>
        </nav>
        <div class="sidebar-tip"><strong>操作提示</strong><p>问答页仅检索已发布知识库。文档处理成功后，请选择生成版本并执行发布。</p></div>
      </aside>

      <main class="admin-content">
        <header class="admin-content-heading"><div><p>当前模块</p><h2>{{ adminViews.find(item => item.id === state.activeView.value)?.label }}</h2><span>{{ viewLead[state.activeView.value] }}</span></div></header>
        <div class="admin-panel">
          <template v-if="state.activeView.value === 'KNOWLEDGE'"><KnowledgeManagement :api="api" /><RetrievalLab :session="session" /></template>
          <template v-else-if="state.activeView.value === 'TRACES'"><TraceList :api="api" @select="selectTrace" /><TraceDetail :api="api" :run-id="selectedTrace?.id ?? null" /></template>
          <UsageDashboard v-else-if="state.activeView.value === 'USAGE'" :api="api" />
          <UserManagement v-else-if="state.activeView.value === 'USERS'" :api="api" />
          <AuditLog v-else-if="state.activeView.value === 'AUDIT'" :api="api" />
          <ModelPolicy v-else :api="api" />
        </div>
      </main>
    </div>
  </section>
</template>

<style scoped>
.admin-workspace { display:grid; height:100%; min-height:0; grid-template-rows:auto minmax(0,1fr); gap:1rem; overflow:hidden; }.admin-hero { display:flex; justify-content:space-between; gap:1rem; align-items:flex-end; padding:clamp(1.25rem,3vw,2rem); border-radius:22px; color:#effff9; background:linear-gradient(116deg,#0d4241 0%,#0f655e 62%,#178173 100%); box-shadow:0 20px 38px rgb(13 77 72 / 17%); }.admin-hero p,.admin-content-heading p { margin:0 0 .35rem; color:#8fe0d1; font-size:.71rem; font-weight:850; letter-spacing:.11em; }.admin-hero h1 { margin:0; font-size:clamp(1.55rem,3vw,2.3rem); letter-spacing:-.045em; }.admin-hero span { display:block; max-width:56rem; margin-top:.6rem; color:#cae9e2; font-size:.86rem; line-height:1.6; }.hero-status { display:grid; min-width:150px; gap:.2rem; justify-items:end; padding:.8rem .9rem; border:1px solid rgb(215 255 244 / 24%); border-radius:14px; background:rgb(4 51 48 / 17%); text-align:right; }.hero-status :deep(.el-icon) { color:#a4eddf; font-size:1.25rem; }.hero-status span { margin:0; color:#effff9; font-size:.8rem; font-weight:800; }.hero-status small { color:#addbd2; font-size:.68rem; }.admin-layout { display:grid; min-height:0; grid-template-columns:220px minmax(0,1fr); gap:1rem; align-items:stretch; }.admin-sidebar { display:grid; min-height:0; gap:.65rem; overflow:auto; padding:.85rem; border:1px solid rgb(255 255 255 / 82%); border-radius:18px; background:rgb(255 255 255 / 78%); box-shadow:0 12px 31px rgb(24 67 60 / 6%); backdrop-filter:blur(14px); }.nav-label { margin:.1rem .35rem; color:#7b918c; font-size:.69rem; font-weight:850; letter-spacing:.08em; }.admin-sidebar nav { display:grid; gap:.25rem; }.admin-sidebar nav button { display:flex; gap:.6rem; align-items:center; width:100%; border:0; border-radius:10px; padding:.68rem .65rem; color:#54736d; background:transparent; text-align:left; font-size:.8rem; font-weight:750; box-shadow:none; }.admin-sidebar nav button:hover { color:#12665d; background:#edf7f3; }.admin-sidebar nav button.selected { color:#fff; background:#14786d; box-shadow:0 8px 14px rgb(20 120 109 / 17%); }.admin-sidebar :deep(.el-icon) { font-size:1rem; }.sidebar-tip { margin-top:.35rem; padding:.75rem; border-radius:12px; color:#5a746f; background:#eff8f5; }.sidebar-tip strong { color:#28685f; font-size:.74rem; }.sidebar-tip p { margin:.35rem 0 0; font-size:.7rem; line-height:1.55; }.admin-content { min-width:0; min-height:0; overflow:auto; padding-right:.15rem; }.admin-content-heading { display:flex; justify-content:space-between; align-items:end; padding:.25rem .15rem .85rem; }.admin-content-heading h2 { margin:0 0 .28rem; color:#1c443f; font-size:1.25rem; letter-spacing:-.025em; }.admin-content-heading span { color:#6d847f; font-size:.79rem; }.admin-content-heading p { color:#318477; }.admin-panel { display:grid; min-height:0; gap:1rem; min-width:0; padding:clamp(.85rem,2vw,1.35rem); border:1px solid rgb(255 255 255 / 90%); border-radius:20px; background:rgb(255 255 255 / 86%); box-shadow:var(--shadow); }
.admin-panel :deep(.admin-card),.admin-panel :deep(.knowledge-admin) { display:grid; gap:1rem; }.admin-panel :deep(.admin-card>header),.admin-panel :deep(.knowledge-admin>header) { display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; padding-bottom:1rem; border-bottom:1px solid #e4eeeb; }.admin-panel :deep(.admin-card h2),.admin-panel :deep(.knowledge-admin h2) { margin:0 0 .35rem; color:#244943; font-size:1.05rem; }.admin-panel :deep(.admin-card header p),.admin-panel :deep(.knowledge-admin header p) { margin:0; color:#708782; line-height:1.55; }.admin-panel :deep(button) { transition:.16s ease; }.admin-panel :deep(button:not(:disabled):hover) { transform:translateY(-1px); }.admin-panel :deep(.table-wrap) { max-height:min(52vh,34rem); overflow:auto; border:1px solid #e1ebe7; border-radius:12px; }.admin-panel :deep(table) { min-width:620px; }.admin-panel :deep(th) { font-size:.73rem; }.admin-panel :deep(td) { font-size:.78rem; }.admin-panel :deep(.filters) { padding:.75rem; border:1px solid #e2ece9; border-radius:12px; background:#f8fbfa; }.admin-panel :deep(.state) { padding:.95rem; border:1px dashed #cfdfda; border-radius:12px; color:#6c837e; background:#fbfdfc; }.admin-panel :deep(.error) { padding:.75rem .85rem; border:1px solid #f2c9c2; border-radius:10px; background:#fff5f3; }.admin-panel :deep(.notice) { padding:.7rem .8rem; border-radius:10px; background:#e9f8f2; }.admin-panel :deep(.metrics div),.admin-panel :deep(.cards article),.admin-panel :deep(.forms form),.admin-panel :deep(.policy) { border-color:#dce9e5; border-radius:12px; background:#fbfefd; }.admin-panel :deep(.metrics div) { background:#eff8f5; }.admin-panel :deep(.plain-list),.admin-panel :deep(.waterfall),.admin-panel :deep(.evidence),.admin-panel :deep(.cards) { max-height:min(48vh,30rem); overflow:auto; padding-right:.25rem; }.admin-panel :deep(button) { border-radius:9px; }
@media (max-width:900px) { .admin-workspace { height:auto; min-height:0; overflow:visible; }.admin-layout { grid-template-columns:1fr; }.admin-sidebar,.admin-content { overflow:visible; }.admin-sidebar nav { display:flex; overflow:auto; padding-bottom:.2rem; }.admin-sidebar nav button { flex:0 0 auto; }.sidebar-tip { display:none; } }.admin-hero { align-items:flex-start; }.hero-status { justify-items:start; text-align:left; }@media (max-width:600px) { .admin-hero { display:grid; border-radius:17px; }.hero-status { min-width:0; grid-template-columns:auto 1fr; align-items:center; }.hero-status small { grid-column:2; }.admin-panel { padding:.8rem; border-radius:16px; }.admin-content-heading { padding:.15rem .15rem .65rem; } }
@media (min-width:721px) and (max-width:900px) { .admin-workspace { height:100%; overflow:hidden; }.admin-sidebar,.admin-content { overflow:auto; } }
</style>
