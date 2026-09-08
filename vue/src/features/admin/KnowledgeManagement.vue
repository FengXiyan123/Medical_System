<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { Check, CircleClose, Collection, DocumentAdd, FolderOpened, MagicStick, Promotion, RefreshRight, UploadFilled } from "@element-plus/icons-vue";
import type { AdminApi, AdminChunk, IngestionStatus, KnowledgeBase, KnowledgeDocument, KnowledgeGeneration } from "./api";

const props = defineProps<{ api: AdminApi }>();
const bases = ref<KnowledgeBase[]>([]);
const documents = ref<KnowledgeDocument[]>([]);
const generations = ref<KnowledgeGeneration[]>([]);
const chunks = ref<AdminChunk[]>([]);
const selectedBase = ref<KnowledgeBase | null>(null);
const selectedDocument = ref<KnowledgeDocument | null>(null);
const selectedGeneration = ref<KnowledgeGeneration | null>(null);
const ingestion = ref<IngestionStatus | null>(null);
const name = ref("");
const description = ref("");
const file = ref<File | null>(null);
const loadingBases = ref(true);
const loadingDocuments = ref(false);
const loadingDetail = ref(false);
const creating = ref(false);
const uploading = ref(false);
const executing = ref(false);
const publishing = ref(false);
const error = ref("");
const notice = ref("");
const editChunkId = ref<string | null>(null);
const editContent = ref("");
const editEnabled = ref(true);
const editRevision = ref(0);

const publishedCount = computed(() => bases.value.filter(base => base.status === "PUBLISHED").length);
const readyGeneration = computed(() => generations.value.find(generation => generation.buildStatus === "BUILD_READY") ?? null);
const activeGeneration = computed(() => generations.value.find(generation => generation.publicationStatus === "ACTIVE") ?? null);
const ingestionStageLabel = computed(() => {
  if (!ingestion.value) return "等待选择文档";
  if (ingestion.value.status === "FAILED") return "处理失败";
  if (ingestion.value.status === "SUCCEEDED") return "处理完成";
  if (ingestion.value.status === "QUEUED") return "等待 Worker 消费";
  if (ingestion.value.stage === "PARSE") return "正在解析文档";
  if (ingestion.value.stage === "CHUNK") return "正在切分文本";
  if (ingestion.value.stage === "EMBED") return "正在写入向量";
  return "正在准备处理";
});

function reportFailure(cause: unknown, fallback: string) {
  error.value = cause instanceof Error ? cause.message : fallback;
  notice.value = "";
}
function clearFeedback() { error.value = ""; notice.value = ""; }

async function loadBases(selectFirst = true) {
  loadingBases.value = true;
  error.value = "";
  try {
    bases.value = await props.api.listKnowledgeBases();
    const current = selectedBase.value && bases.value.find(base => base.id === selectedBase.value?.id);
    if (current) selectedBase.value = current;
    else if (selectFirst && bases.value[0]) await chooseBase(bases.value[0]);
  } catch (cause) {
    reportFailure(cause, "无法加载知识库");
  } finally {
    loadingBases.value = false;
  }
}

async function chooseBase(base: KnowledgeBase) {
  selectedBase.value = base;
  selectedDocument.value = null;
  selectedGeneration.value = null;
  ingestion.value = null;
  generations.value = [];
  chunks.value = [];
  loadingDocuments.value = true;
  error.value = "";
  try {
    documents.value = await props.api.listDocuments(base.id);
    if (documents.value[0]) await chooseDocument(documents.value[0]);
  } catch (cause) {
    documents.value = [];
    reportFailure(cause, "无法加载文档");
  } finally {
    loadingDocuments.value = false;
  }
}

async function chooseDocument(document: KnowledgeDocument) {
  selectedDocument.value = document;
  selectedGeneration.value = null;
  chunks.value = [];
  loadingDetail.value = true;
  error.value = "";
  try {
    const [loadedGenerations, loadedIngestion] = await Promise.all([
      props.api.listGenerations(document.id),
      props.api.ingestionStatus(document.id)
    ]);
    generations.value = loadedGenerations;
    ingestion.value = loadedIngestion;
    const preferred = loadedGenerations.find(generation => generation.publicationStatus === "ACTIVE")
      ?? loadedGenerations.find(generation => generation.buildStatus === "BUILD_READY")
      ?? loadedGenerations[0];
    if (preferred) await chooseGeneration(preferred);
  } catch (cause) {
    generations.value = [];
    ingestion.value = null;
    reportFailure(cause, "无法加载文档处理状态");
  } finally {
    loadingDetail.value = false;
  }
}

async function chooseGeneration(generation: KnowledgeGeneration) {
  selectedGeneration.value = generation;
  editChunkId.value = null;
  error.value = "";
  try {
    const page = await props.api.listChunks(generation.id);
    chunks.value = page.chunks;
    editRevision.value = page.editRevision;
  } catch (cause) {
    chunks.value = [];
    reportFailure(cause, "无法加载切块");
  }
}

async function refreshIngestion() {
  if (!selectedDocument.value) return;
  try {
    ingestion.value = await props.api.ingestionStatus(selectedDocument.value.id);
    if (ingestion.value.status === "SUCCEEDED") await chooseDocument(selectedDocument.value);
  } catch (cause) {
    reportFailure(cause, "无法刷新文档处理状态");
  }
}

async function executeIngestion() {
  if (!selectedDocument.value || executing.value) return;
  executing.value = true;
  clearFeedback();
  try {
    await props.api.executeIngestion(selectedDocument.value.id);
    notice.value = "Agent Core 已开始处理文档。页面会显示解析、切块和向量写入进度。";
    await chooseDocument(selectedDocument.value);
  } catch (cause) {
    reportFailure(cause, "无法启动文档处理");
  } finally {
    executing.value = false;
  }
}

async function create() {
  if (creating.value) return;
  creating.value = true;
  clearFeedback();
  try {
    const created = await props.api.createKnowledgeBase({ name: name.value, description: description.value, accessScope: "ALL_AUTHENTICATED", assignedUserIds: [] });
    name.value = "";
    description.value = "";
    notice.value = "知识库已创建。现在上传第一份资料以生成可发布版本。";
    await loadBases(false);
    await chooseBase(created);
  } catch (cause) {
    reportFailure(cause, "创建知识库失败");
  } finally {
    creating.value = false;
  }
}

function onFileChange(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}

async function upload() {
  if (!selectedBase.value || !file.value || uploading.value) return;
  uploading.value = true;
  clearFeedback();
  try {
    await props.api.uploadDocument(selectedBase.value.id, file.value);
    notice.value = "文件已上传并投递处理队列。选择文档后可查看处理进度。";
    file.value = null;
    await chooseBase(selectedBase.value);
  } catch (cause) {
    reportFailure(cause, "上传文件失败");
  } finally {
    uploading.value = false;
  }
}

async function index() {
  if (!selectedGeneration.value) return;
  clearFeedback();
  try {
    await props.api.indexGeneration(selectedGeneration.value.id);
    notice.value = "索引已构建。请确认切块内容后发布此版本。";
    if (selectedDocument.value) await chooseDocument(selectedDocument.value);
  } catch (cause) {
    reportFailure(cause, "构建索引失败");
  }
}

async function publish() {
  if (!selectedGeneration.value || !selectedDocument.value || publishing.value) return;
  publishing.value = true;
  clearFeedback();
  try {
    await props.api.publishGeneration(selectedDocument.value.id, selectedGeneration.value.id);
    if (selectedBase.value) await props.api.setKnowledgeBaseStatus(selectedBase.value.id, "PUBLISHED");
    notice.value = "生成版本已发布。问答工作台现在可以检索此知识库。";
    await loadBases(false);
    if (selectedDocument.value) await chooseDocument(selectedDocument.value);
  } catch (cause) {
    reportFailure(cause, "发布版本失败");
  } finally {
    publishing.value = false;
  }
}

function beginEdit(chunk: AdminChunk) {
  editChunkId.value = chunk.id;
  editContent.value = chunk.content;
  editEnabled.value = chunk.enabled;
}

async function saveChunk() {
  if (!selectedGeneration.value || !editChunkId.value) return;
  clearFeedback();
  try {
    const current = chunks.value.find(item => item.id === editChunkId.value);
    if (!current) return;
    const payload = editContent.value !== current.content
      ? { content: editContent.value, expectedEditRevision: editRevision.value }
      : { enabled: editEnabled.value, expectedEditRevision: editRevision.value };
    const workspace = await props.api.updateChunk(selectedGeneration.value.id, current.id, payload);
    chunks.value = workspace.chunks;
    editRevision.value = workspace.editRevision;
    editChunkId.value = null;
    notice.value = "切块草稿已保存；重新构建索引后才会进入可发布版本。";
  } catch (cause) {
    reportFailure(cause, "切块保存失败，请刷新后重试");
  }
}

onMounted(() => { void loadBases(); });
</script>

<template>
  <section class="knowledge-admin">
    <header>
      <div><h2>知识库与切块</h2><p>用可见的处理流程管理资料：上传、解析、切块、向量构建，再发布到问答工作台。</p></div>
      <div class="header-metrics"><span>{{ bases.length }} 个知识库</span><span>{{ publishedCount }} 个已发布</span><button type="button" :disabled="loadingBases" @click="() => loadBases()"><el-icon><RefreshRight /></el-icon>刷新</button></div>
    </header>

    <section v-if="error" class="knowledge-alert error" role="alert"><el-icon><CircleClose /></el-icon><div><strong>管理操作未完成</strong><p>{{ error }}</p></div><button type="button" @click="selectedDocument ? chooseDocument(selectedDocument) : loadBases()">重试</button></section>
    <section v-if="notice" class="knowledge-alert success" role="status"><el-icon><Check /></el-icon><div><strong>操作成功</strong><p>{{ notice }}</p></div></section>

    <section class="knowledge-overview">
      <article><span class="overview-icon"><el-icon><Collection /></el-icon></span><div><strong>{{ bases.length }}</strong><small>知识库</small></div></article>
      <article><span class="overview-icon"><el-icon><DocumentAdd /></el-icon></span><div><strong>{{ documents.length }}</strong><small>当前资料</small></div></article>
      <article><span class="overview-icon"><el-icon><Promotion /></el-icon></span><div><strong>{{ publishedCount }}</strong><small>已发布，可供问答</small></div></article>
    </section>

    <div class="knowledge-actions">
      <form class="action-card" @submit.prevent="create"><div class="card-heading"><span><el-icon><FolderOpened /></el-icon></span><div><h3>新建知识库</h3><p>先创建一个可管理的知识范围。</p></div></div><label>知识库名称<input v-model="name" required maxlength="128" placeholder="例如：慢病健康教育" /></label><label>资料说明<textarea v-model="description" maxlength="500" placeholder="说明资料来源、适用范围或版本规则。"></textarea></label><button class="primary-action" type="submit" :disabled="creating">{{ creating ? "正在创建…" : "创建知识库" }}</button></form>
      <form class="action-card" @submit.prevent="upload"><div class="card-heading"><span><el-icon><UploadFilled /></el-icon></span><div><h3>上传资料</h3><p>{{ selectedBase ? `将资料加入「${selectedBase.name}」` : "请先在下方选择知识库" }}</p></div></div><label class="file-picker"><input type="file" accept=".pdf,.docx,.md,.txt" @change="onFileChange" /><span>{{ file ? file.name : "选择 PDF、DOCX、Markdown 或文本文件" }}</span><small>{{ file ? `${Math.ceil(file.size / 1024)} KB` : "单文件最大 20 MB" }}</small></label><button class="primary-action" type="submit" :disabled="!selectedBase || !file || uploading">{{ uploading ? "正在上传…" : "上传并开始处理" }}</button></form>
    </div>

    <section class="knowledge-workbench">
      <aside class="selection-column"><header><h3>1. 选择知识库</h3><small>按访问范围管理资料。</small></header><div v-if="loadingBases" class="column-state">正在加载知识库…</div><div v-else-if="!bases.length" class="column-state empty"><el-icon><Collection /></el-icon><strong>暂无知识库</strong><span>在上方创建第一个知识库。</span></div><button v-for="base in bases" :key="base.id" type="button" class="selection-item" :class="{ selected: selectedBase?.id === base.id }" @click="chooseBase(base)"><strong>{{ base.name }}</strong><span>{{ base.status === "PUBLISHED" ? "已发布" : base.status === "ARCHIVED" ? "已归档" : "草稿" }}</span><small>{{ base.accessScope === "ALL_AUTHENTICATED" ? "全部已登录用户" : "指定用户" }}</small></button></aside>

      <aside class="selection-column"><header><h3>2. 选择文档</h3><small>查看处理状态与版本。</small></header><div v-if="loadingDocuments" class="column-state">正在加载文档…</div><div v-else-if="selectedBase && !documents.length" class="column-state empty"><el-icon><DocumentAdd /></el-icon><strong>暂无资料</strong><span>上传第一份资料后会显示在这里。</span></div><div v-else-if="!selectedBase" class="column-state">先选择一个知识库。</div><button v-for="document in documents" :key="document.id" type="button" class="selection-item document-item" :class="{ selected: selectedDocument?.id === document.id }" @click="chooseDocument(document)"><strong>{{ document.originalFilename }}</strong><span :class="`document-status ${document.status.toLowerCase()}`">{{ document.status === "READY" ? "已就绪" : document.status === "FAILED" ? "失败" : document.status === "PROCESSING" ? "处理中" : "已上传" }}</span><small>版本 v{{ document.latestVersion || 0 }}</small></button></aside>

      <section class="processing-column"><header><div><h3>3. 处理与发布</h3><small>{{ selectedDocument ? selectedDocument.originalFilename : "选择文档后查看处理流程" }}</small></div><button v-if="selectedDocument" type="button" class="small-action" @click="refreshIngestion"><el-icon><RefreshRight /></el-icon>刷新状态</button></header>
        <div v-if="loadingDetail" class="processing-empty">正在加载版本与处理状态…</div>
        <div v-else-if="!selectedDocument" class="processing-empty"><el-icon><MagicStick /></el-icon><strong>等待选择文档</strong><span>文档的切块、向量构建和发布动作会在此显示。</span></div>
        <template v-else>
          <section v-if="ingestion" class="ingestion-flow"><div class="flow-heading"><div><strong>文档处理流程</strong><span>{{ ingestionStageLabel }}</span></div><b>{{ ingestion.status === "SUCCEEDED" ? "100%" : `${ingestion.progress}%` }}</b></div><div class="flow-track"><span :style="{ width: `${ingestion.status === 'SUCCEEDED' ? 100 : ingestion.progress}%` }"></span></div><ol><li :class="{ done: true }"><b>1</b><span>文件上传</span></li><li :class="{ done: ingestion.outboxDispatched, active: !ingestion.outboxDispatched }"><b>2</b><span>任务投递</span></li><li :class="{ done: ingestion.status === 'SUCCEEDED', active: ingestion.status === 'RUNNING' || ingestion.status === 'QUEUED' }"><b>3</b><span>解析与切块</span></li><li :class="{ done: ingestion.status === 'SUCCEEDED' }"><b>4</b><span>生成版本</span></li></ol><p v-if="ingestion.errorCode || ingestion.deliveryError" class="flow-error">{{ ingestion.errorCode ?? ingestion.deliveryError }}</p><button v-if="ingestion.status !== 'SUCCEEDED'" class="secondary-action" type="button" :disabled="executing" @click="executeIngestion">{{ executing ? "正在启动…" : "立即处理此文档" }}</button></section>

          <section class="generation-list"><div class="generation-heading"><strong>生成版本</strong><small>{{ generations.length ? "选择版本后查看切块" : "完成处理后自动生成" }}</small></div><div v-if="!generations.length && ingestion?.status === 'SUCCEEDED'" class="generation-empty">处理已完成，正在刷新生成版本。请点击“刷新状态”。</div><button v-for="generation in generations" :key="generation.id" type="button" class="generation-row" :class="{ selected: selectedGeneration?.id === generation.id, active: generation.publicationStatus === 'ACTIVE' }" @click="chooseGeneration(generation)"><span><strong>v{{ generation.generationNo }}</strong><small>{{ generation.chunkCount }} 个切块</small></span><span>{{ generation.publicationStatus === "ACTIVE" ? "已发布" : generation.buildStatus === "BUILD_READY" ? "可发布" : generation.buildStatus }}</span></button></section>

          <section v-if="ingestion?.status === 'SUCCEEDED' && !activeGeneration" class="publication-guide"><div><el-icon><Promotion /></el-icon></div><div><strong>下一步：发布知识库</strong><p v-if="!selectedGeneration && readyGeneration">文档已完成处理。选择一个可发布版本，再将其发布到问答工作台。</p><p v-else-if="selectedGeneration?.buildStatus === 'BUILD_READY'">此版本包含 {{ selectedGeneration.chunkCount }} 个切块，可以发布。发布后普通用户也能在问答页检索它。</p><p v-else>请选择标记为“可发布”的版本。</p></div><button v-if="!selectedGeneration && readyGeneration" type="button" @click="chooseGeneration(readyGeneration)">选择一个可发布版本</button><button v-else-if="selectedGeneration?.buildStatus === 'BUILD_READY'" type="button" class="publish-button" :disabled="publishing" @click="publish">{{ publishing ? "正在发布…" : "发布版本" }}</button></section>
          <div v-else-if="selectedGeneration" class="generation-actions"><button type="button" :disabled="selectedGeneration.buildStatus !== 'DRAFT'" @click="index">构建索引</button><button type="button" class="publish-button" :disabled="selectedGeneration.buildStatus !== 'BUILD_READY' || publishing" @click="publish">{{ publishing ? "正在发布…" : "发布版本" }}</button></div>
        </template>
      </section>
    </section>

    <section v-if="selectedGeneration" class="chunk-list"><header><div><p>切块预览</p><h3>v{{ selectedGeneration.generationNo }} · {{ chunks.length }} 个切块</h3><span>长文本保留原有换行。草稿版本可以编辑，发布版本只读。</span></div><span class="chunk-readonly">{{ selectedGeneration.buildStatus === "DRAFT" ? "可编辑草稿" : "只读版本" }}</span></header><div v-if="!chunks.length" class="chunk-empty">该版本暂未返回可展示切块。请刷新或检查生成状态。</div><article v-for="chunk in chunks" :key="chunk.id" class="chunk-card"><header><div><strong>#{{ chunk.ordinal + 1 }} · {{ chunk.enabled ? "已启用" : "已禁用" }}</strong><small>{{ chunk.sectionPath ?? "未标记章节" }}<template v-if="chunk.pageStart"> · 第 {{ chunk.pageStart }} 页</template></small></div><span v-if="chunk.manuallyEdited">已人工编辑</span><button v-if="selectedGeneration.buildStatus === 'DRAFT'" type="button" @click="beginEdit(chunk)">编辑</button></header><p>{{ chunk.content }}</p></article><form v-if="editChunkId" class="chunk-editor" @submit.prevent="saveChunk"><header><div><p>编辑切块</p><h3>修改后需重新构建索引</h3></div><button type="button" @click="editChunkId = null">取消</button></header><textarea v-model="editContent" required></textarea><label class="enabled-toggle"><input v-model="editEnabled" type="checkbox" />启用此切块</label><button class="primary-action" type="submit">保存切块草稿</button></form></section>
  </section>
</template>

<style scoped>
.knowledge-admin { display:grid; gap:1rem; }.knowledge-admin h2,.knowledge-admin h3,.knowledge-admin p { margin:0; }.header-metrics { display:flex; flex-wrap:wrap; gap:.45rem; align-items:center; }.header-metrics span { border:1px solid #d5e8e2; border-radius:999px; padding:.34rem .5rem; color:#4d726b; background:#f7fbf9; font-size:.71rem; font-weight:750; }.header-metrics button,.small-action { display:flex; gap:.3rem; align-items:center; border:1px solid #b8d8d0; border-radius:9px; padding:.45rem .58rem; color:#176d63; background:#fff; font-size:.74rem; font-weight:800; box-shadow:none; }.knowledge-alert { display:flex; gap:.65rem; align-items:flex-start; padding:.75rem .85rem; border-radius:12px; }.knowledge-alert :deep(.el-icon) { margin-top:.12rem; font-size:1.05rem; }.knowledge-alert div { flex:1; }.knowledge-alert strong { font-size:.78rem; }.knowledge-alert p { margin-top:.15rem; font-size:.76rem; line-height:1.5; }.knowledge-alert button { border:1px solid currentColor; border-radius:8px; padding:.38rem .52rem; color:inherit; background:#fff; font-size:.73rem; font-weight:750; }.knowledge-alert.error { border:1px solid #f1c6bf; }.knowledge-alert.success { border:1px solid #bce3d7; }.knowledge-overview { display:grid; grid-template-columns:repeat(3,1fr); gap:.65rem; }.knowledge-overview article { display:flex; gap:.65rem; align-items:center; padding:.8rem; border:1px solid #dfece7; border-radius:13px; background:#fbfefd; }.overview-icon { display:grid; width:2.1rem; height:2.1rem; place-items:center; border-radius:10px; color:#13776c; background:#e3f5ef; }.knowledge-overview article div { display:grid; gap:.05rem; }.knowledge-overview strong { color:#214b45; font-size:1.08rem; }.knowledge-overview small { color:#738a85; font-size:.7rem; }.knowledge-actions { display:grid; grid-template-columns:1fr 1fr; gap:.8rem; }.action-card { display:grid; gap:.7rem; padding:1rem; border:1px solid #dceae5; border-radius:15px; background:#fbfefd; }.card-heading { display:flex; gap:.65rem; align-items:flex-start; }.card-heading>span { display:grid; flex:0 0 auto; width:2.1rem; height:2.1rem; place-items:center; border-radius:10px; color:#14786d; background:#e5f6f0; }.card-heading h3 { margin-bottom:.18rem; color:#2d5751; font-size:.88rem; }.card-heading p { color:#748b86; font-size:.73rem; line-height:1.45; }.action-card label { display:grid; gap:.35rem; color:#52716c; font-size:.73rem; font-weight:750; }.action-card textarea { min-height:76px; }.primary-action,.secondary-action,.publish-button { border:0; border-radius:10px; padding:.58rem .75rem; color:#fff; background:#16796e; font-size:.78rem; font-weight:800; }.primary-action:disabled,.secondary-action:disabled,.publish-button:disabled { opacity:.52; }.file-picker { position:relative; min-height:84px; align-content:center; border:1px dashed #a9cfc6; border-radius:11px; padding:.75rem; background:#f4faf8; cursor:pointer; }.file-picker input { position:absolute; inset:0; opacity:0; cursor:pointer; }.file-picker span { overflow:hidden; color:#36675f; text-overflow:ellipsis; white-space:nowrap; }.file-picker small { color:#81948f; }.knowledge-workbench { display:grid; height:min(500px,52vh); min-height:360px; grid-template-columns:minmax(160px,.7fr) minmax(160px,.7fr) minmax(300px,1.55fr); overflow:hidden; border:1px solid #dce9e5; border-radius:16px; background:#fff; }.selection-column,.processing-column { display:grid; min-height:0; align-content:start; gap:.45rem; min-width:0; overflow:auto; padding:.85rem; border-right:1px solid #e1ece8; background:#fbfefd; }.processing-column { grid-template-rows:auto auto 1fr; border-right:0; background:#fff; }.selection-column>header,.processing-column>header { position:sticky; top:-.85rem; z-index:1; display:flex; justify-content:space-between; gap:.5rem; align-items:flex-start; padding:.85rem .1rem .65rem; border-bottom:1px solid #e8f0ed; background:inherit; }.selection-column h3,.processing-column h3 { margin:0 0 .15rem; color:#2a554e; font-size:.82rem; }.selection-column small,.processing-column small { color:#80938e; font-size:.68rem; }.selection-item { display:grid; gap:.18rem; width:100%; border:1px solid transparent; border-radius:10px; padding:.68rem .65rem; color:#426861; background:transparent; text-align:left; box-shadow:none; }.selection-item:hover { border-color:#c5e0d8; background:#f0f9f5; }.selection-item.selected { border-color:#158071; color:#fff; background:#158071; box-shadow:0 7px 13px rgb(20 119 107 / 16%); }.selection-item strong { overflow:hidden; font-size:.76rem; text-overflow:ellipsis; white-space:nowrap; }.selection-item span { font-size:.67rem; font-weight:800; }.selection-item small { color:#78908b; font-size:.64rem; }.selection-item.selected small { color:#d8f3eb; }.document-status.failed { color:#b42318; }.document-status.ready { color:#177267; }.selection-item.selected .document-status { color:#fff; }.column-state { padding:.9rem .4rem; color:#718782; font-size:.73rem; line-height:1.5; }.column-state.empty { display:grid; gap:.38rem; place-items:center; margin:auto 0; color:#7a908b; text-align:center; }.column-state.empty :deep(.el-icon) { color:#63a99b; font-size:1.4rem; }.column-state.empty strong { color:#486b65; font-size:.76rem; }.processing-empty { display:grid; gap:.45rem; min-height:150px; place-content:center; place-items:center; padding:1rem; color:#7a908b; text-align:center; font-size:.76rem; }.processing-empty :deep(.el-icon) { color:#5ca99b; font-size:1.5rem; }.processing-empty strong { color:#466b64; }.ingestion-flow { display:grid; gap:.65rem; padding:.85rem; border:1px solid #d5e7e1; border-radius:13px; background:#f5fbf9; }.flow-heading { display:flex; justify-content:space-between; gap:.5rem; align-items:center; }.flow-heading div { display:grid; gap:.1rem; }.flow-heading strong { color:#2e5c54; font-size:.78rem; }.flow-heading span { color:#6b847f; font-size:.68rem; }.flow-heading b { color:#14776b; font-size:.85rem; }.flow-track { height:.38rem; overflow:hidden; border-radius:999px; background:#dcece7; }.flow-track span { display:block; height:100%; border-radius:inherit; background:linear-gradient(90deg,#43ad9a,#157a6d); transition:width .3s ease; }.ingestion-flow ol { display:grid; grid-template-columns:repeat(4,1fr); gap:.25rem; margin:0; padding:0; list-style:none; }.ingestion-flow li { display:grid; gap:.25rem; justify-items:center; color:#82958f; font-size:.62rem; text-align:center; }.ingestion-flow li b { display:grid; width:1.3rem; height:1.3rem; place-items:center; border-radius:50%; color:#76908a; background:#e1ece8; font-size:.64rem; }.ingestion-flow li.done,.ingestion-flow li.active { color:#276e64; }.ingestion-flow li.done b { color:#fff; background:#218777; }.ingestion-flow li.active b { color:#126d62; background:#bde8de; box-shadow:0 0 0 4px rgb(33 135 119 / 10%); }.flow-error { padding:.52rem; border-radius:8px; color:#a6392c; background:#fff1ef; font-size:.7rem; }.secondary-action { color:#137267; background:#e2f5ef; }.generation-list { display:grid; max-height:180px; gap:.45rem; margin-top:.15rem; overflow:auto; padding-right:.15rem; }.generation-heading { position:sticky; top:0; z-index:1; display:flex; justify-content:space-between; gap:.5rem; align-items:center; padding-bottom:.15rem; background:#fff; }.generation-heading strong { color:#456960; font-size:.75rem; }.generation-heading small { color:#80938e; }.generation-empty { padding:.7rem; border:1px dashed #cfdfda; border-radius:10px; color:#718782; font-size:.72rem; }.generation-row { display:flex; justify-content:space-between; gap:.5rem; align-items:center; width:100%; border:1px solid #dfeae6; border-radius:10px; padding:.6rem .65rem; color:#54726d; background:#fff; text-align:left; box-shadow:none; }.generation-row>span:first-child { display:grid; gap:.1rem; }.generation-row strong { font-size:.76rem; }.generation-row small { font-size:.65rem; }.generation-row>span:last-child { border-radius:999px; padding:.22rem .4rem; color:#39736a; background:#eef8f4; font-size:.65rem; font-weight:800; }.generation-row.selected { border-color:#168173; background:#e7f6f1; }.generation-row.active { border-color:#50a998; }.publication-guide { display:grid; grid-template-columns:auto 1fr auto; gap:.6rem; align-items:center; margin-top:.65rem; padding:.75rem; border:1px solid #b9dfd4; border-radius:12px; background:linear-gradient(135deg,#effbf6,#e4f5ef); }.publication-guide>div:first-child { display:grid; width:2rem; height:2rem; place-items:center; border-radius:9px; color:#fff; background:#188273; }.publication-guide>div:nth-child(2) { min-width:0; }.publication-guide strong { color:#23645b; font-size:.77rem; }.publication-guide p { margin-top:.18rem; color:#64827b; font-size:.69rem; line-height:1.45; }.publication-guide button,.generation-actions button { border:0; border-radius:9px; padding:.48rem .6rem; color:#176e63; background:#fff; font-size:.72rem; font-weight:800; white-space:nowrap; }.publication-guide .publish-button,.generation-actions .publish-button { color:#fff; background:#15796e; }.generation-actions { display:flex; gap:.45rem; margin-top:.6rem; }.generation-actions button { border:1px solid #c3ded6; }.chunk-list { display:grid; max-height:min(520px,60vh); gap:.7rem; overflow:auto; padding:.25rem .35rem 0 0; }.chunk-list>header,.chunk-card>header,.chunk-editor>header { display:flex; justify-content:space-between; gap:.75rem; align-items:flex-start; }.chunk-list>header { position:sticky; top:0; z-index:1; padding:.1rem .1rem .65rem; border-bottom:1px solid #e3ede9; background:#fff; }.chunk-list>header p,.chunk-editor>header p { margin:0 0 .16rem; color:#3b897d; font-size:.68rem; font-weight:850; letter-spacing:.08em; }.chunk-list>header h3,.chunk-editor>header h3 { margin:0 0 .24rem; color:#2b534c; font-size:.95rem; }.chunk-list>header span { color:#748b86; font-size:.72rem; }.chunk-readonly { flex:0 0 auto; border-radius:999px; padding:.3rem .48rem; color:#467168 !important; background:#eff7f4; }.chunk-empty { padding:1rem; border:1px dashed #d3e2dd; border-radius:12px; color:#748b86; font-size:.76rem; }.chunk-card { display:grid; gap:.6rem; padding:.9rem; border:1px solid #dfeae6; border-radius:13px; background:#fff; }.chunk-card>header strong { display:block; margin-bottom:.16rem; color:#315c54; font-size:.76rem; }.chunk-card>header small { color:#7b918c; font-size:.68rem; }.chunk-card>header span { border-radius:999px; padding:.22rem .4rem; color:#8b6100; background:#fff5df; font-size:.65rem; }.chunk-card>header button,.chunk-editor>header button { border:1px solid #bfdcd4; border-radius:8px; padding:.38rem .5rem; color:#166f64; background:#fff; font-size:.7rem; font-weight:750; }.chunk-card p { max-height:14rem; margin:0; overflow:auto; color:#426760; font-size:.8rem; line-height:1.7; white-space:pre-wrap; }.chunk-editor { display:grid; gap:.65rem; padding:1rem; border:1px solid #88c7b9; border-radius:13px; background:#effaf6; }.chunk-editor textarea { min-height:170px; }.enabled-toggle { display:flex; gap:.4rem; align-items:center; color:#466960; font-size:.75rem; }.enabled-toggle input { width:auto; accent-color:#168173; }
@media (max-width:1050px) { .knowledge-workbench { grid-template-columns:1fr 1fr; }.processing-column { grid-column:1/-1; border-top:1px solid #e1ece8; }.selection-column:nth-child(2) { border-right:0; } }.knowledge-overview { grid-template-columns:1fr 1fr 1fr; }@media (max-width:680px) { .knowledge-overview,.knowledge-actions { grid-template-columns:1fr; }.knowledge-workbench { grid-template-columns:1fr; }.selection-column,.processing-column,.selection-column:nth-child(2) { border-right:0; border-bottom:1px solid #e1ece8; }.processing-column { grid-column:auto; border-bottom:0; }.header-metrics { justify-content:flex-start; }.publication-guide { grid-template-columns:auto 1fr; }.publication-guide button { grid-column:1/-1; }.ingestion-flow ol { grid-template-columns:repeat(2,1fr); }.chunk-list>header { display:grid; }.chunk-readonly { justify-self:start; } }
</style>
