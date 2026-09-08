<script setup lang="ts">
import { computed, nextTick, onMounted, reactive, ref } from "vue";
import { ChatDotRound, Delete, Document, Plus, RefreshRight, WarningFilled } from "@element-plus/icons-vue";

import type { AuthSession } from "../auth/session";
import { ChatApi, type AccessibleKnowledgeBase, type ChatMessage, type Conversation } from "./api";
import { executionModes, type ExecutionModeId } from "./modes";
import { shouldSubmitOnEnter } from "./composerKeys";
import { applyRunEvent, connectRunStream, createRunStreamState, type RunStreamConnection } from "./runStream";
import RunExecutionSummary from "./RunExecutionSummary.vue";
import FeedbackControl from "./FeedbackControl.vue";

const props = defineProps<{
  session: AuthSession;
  mode: ExecutionModeId;
  selectedKnowledgeBaseIds: string[];
  knowledgeBases: AccessibleKnowledgeBase[];
  knowledgeError: string;
}>();
const emit = defineEmits<{ modeChange: [mode: ExecutionModeId]; knowledgeBaseIdsChange: [ids: string[]] }>();
const api = computed(() => new ChatApi(props.session));
const conversations = ref<Conversation[]>([]);
const activeConversationId = ref<string | null>(null);
const messages = ref<ChatMessage[]>([]);
const question = ref("");
const error = ref<string | null>(null);
const loadingConversations = ref(true);
const loadingMessages = ref(false);
const creatingConversation = ref(false);
const submitting = ref(false);
const deletingConversationId = ref<string | null>(null);
const deleteCandidateId = ref<string | null>(null);
const messageList = ref<HTMLOListElement | null>(null);
let connection: RunStreamConnection | null = null;
let activeRun: { runId: string; state: ReturnType<typeof createRunStreamState>; message: ChatMessage } | null = null;

const activeConversation = computed(() => conversations.value.find(item => item.id === activeConversationId.value) ?? null);
const selectedMode = computed(() => executionModes.find(item => item.id === props.mode) ?? executionModes[0]);
const canSend = computed(() => Boolean(question.value.trim() && activeConversationId.value && !submitting.value));

onMounted(() => { void loadConversations(); });

async function loadConversations() {
  loadingConversations.value = true;
  error.value = null;
  try {
    conversations.value = await api.value.listConversations();
    if (activeConversationId.value && conversations.value.some(item => item.id === activeConversationId.value)) {
      await selectConversation(activeConversationId.value);
    } else if (conversations.value[0]) {
      await selectConversation(conversations.value[0].id);
    } else {
      activeConversationId.value = null;
      messages.value = [];
    }
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    loadingConversations.value = false;
  }
}

async function createConversation() {
  if (creatingConversation.value) return;
  creatingConversation.value = true;
  error.value = null;
  try {
    const conversation = await api.value.createConversation("新的健康咨询");
    conversations.value.unshift(conversation);
    await selectConversation(conversation.id);
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    creatingConversation.value = false;
  }
}

async function selectConversation(conversationId: string) {
  if (conversationId === activeConversationId.value && !loadingMessages.value) return;
  connection?.stop();
  activeRun = null;
  activeConversationId.value = conversationId;
  messages.value = [];
  error.value = null;
  loadingMessages.value = true;
  try {
    messages.value = (await api.value.messages(conversationId)).map(readMessage);
    await scrollToNewest();
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    loadingMessages.value = false;
  }
}

function askDelete(conversation: Conversation) {
  if (submitting.value && conversation.id === activeConversationId.value) return;
  deleteCandidateId.value = conversation.id;
}

async function deleteConversation(conversation: Conversation) {
  if (deletingConversationId.value) return;
  deletingConversationId.value = conversation.id;
  error.value = null;
  try {
    if (conversation.id === activeConversationId.value) {
      connection?.stop();
      activeRun = null;
    }
    await api.value.deleteConversation(conversation.id);
    conversations.value = conversations.value.filter(item => item.id !== conversation.id);
    deleteCandidateId.value = null;
    if (conversation.id === activeConversationId.value) {
      activeConversationId.value = null;
      messages.value = [];
      question.value = "";
      if (conversations.value[0]) await selectConversation(conversations.value[0].id);
    }
  } catch (cause) {
    error.value = messageOf(cause);
  } finally {
    deletingConversationId.value = null;
  }
}

function useSuggestion(value: string) {
  question.value = value;
}

async function send() {
  const content = question.value.trim();
  const conversationId = activeConversationId.value;
  if (!content || !conversationId || submitting.value) return;
  submitting.value = true;
  error.value = null;
  const userMessage: ChatMessage = {
    id: `local-${crypto.randomUUID()}`,
    runId: null,
    role: "USER",
    content,
    citationManifest: null,
    createdAt: new Date().toISOString()
  };
  messages.value.push(userMessage);
  question.value = "";
  await scrollToNewest();
  try {
    const accepted = await api.value.submitRun(
      conversationId,
      content,
      props.mode,
      props.mode === "MANUAL_KB" ? props.selectedKnowledgeBaseIds : []
    );
    const assistant = reactive<ChatMessage>({
      id: `run-${accepted.run.id}`,
      runId: accepted.run.id,
      role: "ASSISTANT",
      content: "",
      citationManifest: null,
      createdAt: new Date().toISOString(),
      streaming: true
    });
    messages.value.push(assistant);
    const state = createRunStreamState();
    activeRun = { runId: accepted.run.id, state, message: assistant };
    connection = connectRunStream(api.value.streamUrl(conversationId, accepted.run.id), {
      accessToken: props.session.accessToken,
      onEvent: event => {
        if (activeRun?.runId !== accepted.run.id) return;
        applyRunEvent(state, event);
        assistant.content = state.answer.value;
        assistant.citations = state.citations.value;
        assistant.summary = state.summary.value;
        assistant.execution = {
          route: state.route.value,
          steps: state.steps.value,
          budgetTermination: state.budgetTermination.value
        };
        assistant.streaming = state.status.value === "RUNNING" || state.status.value === "IDLE";
        void scrollToNewest();
      },
      onError: streamError => {
        assistant.streaming = false;
        error.value = streamError;
      }
    });
    await connection.done;
  } catch (cause) {
    messages.value = messages.value.filter(item => item !== userMessage);
    error.value = messageOf(cause);
  } finally {
    submitting.value = false;
    activeRun = null;
  }
}

async function stop() {
  const conversationId = activeConversationId.value;
  if (!activeRun || !conversationId) return;
  const run = activeRun;
  connection?.stop();
  try {
    await api.value.cancelRun(conversationId, run.runId);
    run.message.streaming = false;
  } catch (cause) {
    error.value = messageOf(cause);
  }
}

function handleComposerKeydown(event: KeyboardEvent) {
  if (!shouldSubmitOnEnter(event) || !canSend.value) return;
  event.preventDefault();
  void send();
}

async function retryMessages() {
  if (activeConversationId.value) await selectConversation(activeConversationId.value);
  else await loadConversations();
}

async function scrollToNewest() {
  await nextTick();
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight;
}

function readMessage(message: ChatMessage): ChatMessage {
  return { ...message, citations: parseCitations(message.citationManifest) };
}
function parseCitations(value: string | null): unknown[] {
  try { return value ? JSON.parse(value) as unknown[] : []; } catch { return []; }
}
function messageOf(cause: unknown): string {
  return cause instanceof Error ? cause.message : "操作失败，请稍后重试。";
}
function toggleKnowledgeBase(id: string) {
  const next = props.selectedKnowledgeBaseIds.includes(id)
    ? props.selectedKnowledgeBaseIds.filter(item => item !== id)
    : [...props.selectedKnowledgeBaseIds, id];
  emit("knowledgeBaseIdsChange", next);
}
</script>

<template>
  <section class="chat-shell" aria-label="对话工作台">
    <aside class="conversation-list">
      <header class="conversation-heading">
        <div><p>会话历史</p><h1>我的对话</h1></div>
      </header>

      <button class="new-conversation" type="button" :disabled="creatingConversation" @click="createConversation">
        <el-icon><Plus /></el-icon>{{ creatingConversation ? "正在创建…" : "新建会话" }}
      </button>

      <div v-if="loadingConversations" class="conversation-state"><span class="small-spinner"></span><span>正在加载会话…</span></div>
      <div v-else-if="!conversations.length" class="conversation-empty">
        <el-icon><ChatDotRound /></el-icon>
        <strong>还没有会话</strong>
        <p>从一个健康知识问题开始，系统会为你保留可追溯的对话记录。</p>
        <button type="button" @click="createConversation">创建第一条会话</button>
      </div>
      <nav v-else class="conversation-items" aria-label="会话列表">
        <article v-for="conversation in conversations" :key="conversation.id" class="conversation-row" :class="{ active: conversation.id === activeConversationId }">
          <button class="conversation-item" type="button" @click="selectConversation(conversation.id)">
            <strong>{{ conversation.title }}</strong><small>点击继续对话</small>
          </button>
          <button class="conversation-delete" type="button" :disabled="deletingConversationId === conversation.id || (conversation.id === activeConversationId && submitting)" :aria-label="`删除会话：${conversation.title}`" title="删除会话" @click="askDelete(conversation)"><el-icon><Delete /></el-icon></button>
        </article>
      </nav>

      <section v-if="deleteCandidateId" class="delete-confirm" role="alert">
        <p>删除后该会话及其消息将无法恢复。</p>
        <div><button type="button" class="quiet" @click="deleteCandidateId = null">取消</button><button type="button" class="danger" :disabled="deletingConversationId === deleteCandidateId" @click="deleteConversation(conversations.find(item => item.id === deleteCandidateId)!)">确认删除</button></div>
      </section>
    </aside>

    <section class="chat-main">
      <header class="chat-header">
        <div><p class="section-kicker">健康知识问答</p><h2>{{ activeConversation?.title ?? "开始一段新对话" }}</h2><p>回答仅用于教学演示，请结合专业人员意见。</p></div>
        <span class="mode-status">{{ selectedMode.label }}</span>
      </header>

      <section v-if="error" class="inline-alert" role="alert">
        <el-icon><WarningFilled /></el-icon><div><strong>操作未完成</strong><p>{{ error }}</p></div><button type="button" @click="retryMessages"><el-icon><RefreshRight /></el-icon>重试</button>
      </section>

      <ol ref="messageList" class="messages" aria-live="polite">
        <li v-if="loadingMessages" class="message-loading"><span class="small-spinner"></span>正在加载此会话的消息…</li>
        <li v-else-if="!activeConversationId" class="chat-empty-state">
          <div class="empty-icon"><el-icon><ChatDotRound /></el-icon></div>
          <h3>创建一条会话，开始提问</h3>
          <p>你可以询问疾病健康教育、日常监测、药物知识等内容。系统会根据当前模式选择已授权的知识与工具。</p>
          <button type="button" @click="createConversation"><el-icon><Plus /></el-icon>创建第一条会话</button>
        </li>
        <li v-else-if="!messages.length" class="chat-empty-state">
          <div class="empty-icon"><el-icon><Document /></el-icon></div>
          <h3>这是一段新的健康咨询</h3>
          <p>选择下方执行方式，输入问题后按 Enter 发送；Shift + Enter 可换行。</p>
          <div class="suggestion-row"><button type="button" @click="useSuggestion('高血压患者日常生活中需要注意什么？')">高血压日常管理</button><button type="button" @click="useSuggestion('家庭血压监测应该如何记录？')">家庭血压监测</button><button type="button" @click="useSuggestion('抗生素为什么不能自行停药？')">合理使用抗生素</button></div>
        </li>
        <li v-for="message in messages" :key="message.id" :class="['message', message.role.toLowerCase()]">
          <div class="message-meta"><span class="message-avatar">{{ message.role === "USER" ? "你" : "医" }}</span><strong>{{ message.role === "USER" ? "你" : "医疗助手" }}</strong><small v-if="message.streaming"><span class="stream-dot"></span>正在生成</small></div>
          <p class="message-body">{{ message.content || (message.streaming ? "正在组织回答…" : "") }}</p>
          <details v-if="message.citations?.length" class="message-details"><summary>查看引用知识 <span>{{ message.citations.length }}</span></summary><pre>{{ message.citations }}</pre></details>
          <details v-if="message.summary || message.execution?.route || message.execution?.steps.length || message.execution?.budgetTermination" class="message-details"><summary>查看执行过程</summary><RunExecutionSummary :route="message.execution?.route ?? null" :steps="message.execution?.steps ?? []" :budget-termination="message.execution?.budgetTermination ?? null" :summary="message.summary ?? null" /></details>
          <FeedbackControl v-if="message.role === 'ASSISTANT'" :session="session" :answer-id="message.id" :disabled="message.streaming || message.id.startsWith('run-')" />
        </li>
      </ol>

      <form class="composer" @submit.prevent="send">
        <section class="mode-rail" aria-label="本轮执行方式">
          <div class="mode-copy"><span>本轮执行方式</span><small>{{ selectedMode.description }}</small></div>
          <div class="mode-options">
            <button v-for="item in executionModes" :key="item.id" type="button" class="mode-chip" :class="{ selected: mode === item.id }" :aria-pressed="mode === item.id" @click="emit('modeChange', item.id)"><strong>{{ item.label }}</strong><small>{{ item.id === "AUTO_KB" ? "系统选择" : item.id === "MANUAL_KB" ? "限定范围" : "Agent 决策" }}</small></button>
          </div>
        </section>
        <section v-if="mode === 'MANUAL_KB'" class="knowledge-strip">
          <div><strong>限定知识库</strong><small>至少选择一个已发布知识库后再发送。</small></div>
          <p v-if="knowledgeError" class="knowledge-error">{{ knowledgeError }}</p>
          <div v-else-if="knowledgeBases.length" class="knowledge-options"><button v-for="knowledgeBase in knowledgeBases" :key="knowledgeBase.id" type="button" class="knowledge-chip" :class="{ selected: selectedKnowledgeBaseIds.includes(knowledgeBase.id) }" :aria-pressed="selectedKnowledgeBaseIds.includes(knowledgeBase.id)" @click="toggleKnowledgeBase(knowledgeBase.id)">{{ knowledgeBase.name }}</button></div>
          <div v-else class="knowledge-empty"><el-icon><WarningFilled /></el-icon><span>暂无已发布知识库。请由管理员在管理后台发布已构建的生成版本。</span></div>
        </section>
        <div class="input-frame">
          <textarea v-model="question" :disabled="!activeConversationId || submitting" maxlength="10000" placeholder="输入健康知识问题，例如：高血压患者日常生活中需要注意什么？" @keydown="handleComposerKeydown" />
          <div class="composer-actions"><small><kbd>Enter</kbd> 发送 <span>·</span> <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行</small><button type="button" class="stop-button" :disabled="!activeRun" @click="stop">停止生成</button><button type="submit" class="send-button" :disabled="!canSend">{{ submitting ? "正在发送…" : "发送问题" }}</button></div>
        </div>
      </form>
    </section>
  </section>
</template>

<style scoped>
.chat-shell { display:grid; height:100%; min-height:0; grid-template-columns:270px minmax(0,1fr); overflow:hidden; border:1px solid rgb(255 255 255 / 86%); border-radius:22px; background:#fff; box-shadow:var(--shadow); }.conversation-list { display:flex; min-height:0; flex-direction:column; overflow:hidden; padding:1rem; border-right:1px solid #dce9e5; background:linear-gradient(180deg,#f2faf7 0%,#e9f4f0 100%); }.conversation-heading { display:flex; justify-content:space-between; gap:.75rem; align-items:center; padding:.2rem .1rem .8rem; }.conversation-heading p,.section-kicker { margin:0 0 .15rem; color:#398578; font-size:.76rem; font-weight:850; letter-spacing:.08em; }.conversation-heading h1 { margin:0; font-size:1.16rem; letter-spacing:-.02em; }.new-conversation { display:flex; justify-content:center; gap:.45rem; align-items:center; width:100%; min-height:42px; border:0; border-radius:10px; color:#fff; background:#15796e; font-size:.9rem; font-weight:800; box-shadow:0 8px 15px rgb(21 121 110 / 18%); }.conversation-items { display:grid; flex:1; min-height:0; align-content:start; grid-auto-rows:max-content; gap:.5rem; margin-top:1rem; overflow-x:hidden; overflow-y:auto; overscroll-behavior:contain; scrollbar-gutter:stable; padding-right:.18rem; }.conversation-row { display:grid; grid-template-columns:minmax(0,1fr) auto; gap:.2rem; align-items:center; border:1px solid transparent; border-radius:12px; transition:.16s ease; }.conversation-row.active { border-color:#15796e; background:#15796e; box-shadow:0 8px 16px rgb(21 121 110 / 17%); }.conversation-item { display:grid; min-width:0; gap:.25rem; padding:.78rem .72rem; border:0; border-radius:10px; color:#355b56; background:transparent; text-align:left; box-shadow:none; }.conversation-item strong { overflow:hidden; font-size:.9rem; text-overflow:ellipsis; white-space:nowrap; }.conversation-item small { color:#79918c; font-size:.77rem; }.conversation-row.active .conversation-item,.conversation-row.active .conversation-item small { color:#fff; }.conversation-delete { display:grid; width:2rem; height:2rem; place-items:center; margin-right:.32rem; border:0; border-radius:8px; color:#708b85; background:transparent; box-shadow:none; }.conversation-delete:hover:not(:disabled) { color:#a72317; background:#fff3f1; }.conversation-row.active .conversation-delete { color:#d2efe9; }.conversation-state,.conversation-empty { display:grid; gap:.65rem; place-items:center; margin:auto 0; padding:1rem .45rem; color:#6e8580; text-align:center; }.conversation-empty :deep(.el-icon) { color:#62a69a; font-size:1.7rem; }.conversation-empty strong { color:#365d57; font-size:.92rem; }.conversation-empty p { margin:0; font-size:.84rem; line-height:1.6; }.conversation-empty button { border:1px solid #aad1c9; border-radius:9px; padding:.5rem .68rem; color:#176d63; background:#fff; font-size:.82rem; font-weight:750; }.delete-confirm { display:grid; gap:.55rem; flex:0 0 auto; margin-top:.75rem; padding:.75rem; border:1px solid #f3c5be; border-radius:12px; background:#fff5f3; }.delete-confirm p { margin:0; color:#8a362a; font-size:.82rem; line-height:1.45; }.delete-confirm div { display:flex; gap:.4rem; }.delete-confirm button { flex:1; border:0; border-radius:8px; padding:.46rem; font-size:.8rem; font-weight:750; }.quiet { color:#6b7f7a; background:#fff; }.danger { color:#fff; background:#b42318; }
 .chat-main { display:grid; min-width:0; min-height:0; grid-template-rows:auto minmax(0,1fr) auto; padding:clamp(1rem,2vw,1.6rem); background:linear-gradient(180deg,#fff 0%,#fbfdfc 100%); }.chat-header { display:flex; justify-content:space-between; gap:1rem; align-items:flex-start; padding-bottom:1rem; border-bottom:1px solid #e4eeeb; }.chat-header h2 { margin:0 0 .4rem; font-size:clamp(1.32rem,2vw,1.7rem); letter-spacing:-.035em; }.chat-header p:last-child { margin:0; color:#6d847f; font-size:.9rem; }.mode-status { flex:0 0 auto; border:1px solid #cde4dd; border-radius:999px; padding:.42rem .66rem; color:#176d63; background:#effaf6; font-size:.8rem; font-weight:800; }.inline-alert { display:flex; gap:.65rem; align-items:flex-start; margin-top:.9rem; padding:.82rem .92rem; border:1px solid #f2c8c1; border-radius:12px; color:#84382c; background:#fff5f3; }.inline-alert :deep(.el-icon) { margin-top:.12rem; font-size:1.1rem; }.inline-alert div { min-width:0; flex:1; }.inline-alert strong { font-size:.9rem; }.inline-alert p { margin:.18rem 0 0; font-size:.84rem; line-height:1.45; }.inline-alert button { display:flex; gap:.25rem; align-items:center; border:1px solid #e7b1a9; border-radius:8px; padding:.45rem .58rem; color:#943b2d; background:#fff; font-size:.82rem; font-weight:750; box-shadow:none; }.messages { display:grid; min-height:0; gap:1.1rem; align-content:start; margin:0; padding:1.4rem .2rem; overflow:auto; list-style:none; scrollbar-color:#bed7d0 transparent; }.message { width:min(82%, 800px); padding:1rem 1.05rem; border:1px solid #e1ece8; border-radius:4px 18px 18px; background:#f6faf9; box-shadow:0 6px 16px rgb(27 69 62 / 4%); }.message.user { justify-self:end; border:0; border-radius:18px 4px 18px 18px; color:#fff; background:linear-gradient(135deg,#168277,#0e635d); box-shadow:0 10px 20px rgb(13 90 83 / 16%); }.message-meta { display:flex; gap:.45rem; align-items:center; color:#47706a; font-size:.84rem; }.message.user .message-meta { color:#e9fffa; }.message-avatar { display:grid; width:1.55rem; height:1.55rem; place-items:center; border-radius:50%; color:#176d63; background:#dff2ec; font-size:.72rem; font-weight:850; }.message.user .message-avatar { color:#0c6159; background:#d3f3ea; }.message-meta small { display:flex; gap:.3rem; align-items:center; margin-left:auto; color:#5c827b; font-size:.76rem; }.stream-dot { width:.42rem; height:.42rem; border-radius:50%; background:#18aa8f; animation:blink 1s infinite; }@keyframes blink { 50% { opacity:.28; } }.message-body { max-width:70ch; margin:.65rem 0 0; overflow-wrap:anywhere; white-space:pre-wrap; font-size:1rem; line-height:1.75; }.message-details { margin-top:.7rem; border-top:1px solid #dce9e5; padding-top:.55rem; color:#486b66; }.message.user .message-details { border-color:rgb(255 255 255 / 25%); color:#effffb; }.message-details summary { cursor:pointer; font-size:.84rem; font-weight:750; }.message-details summary span { display:inline-grid; min-width:1.25rem; place-items:center; border-radius:999px; padding:.04rem .3rem; color:#14695f; background:#dff3ec; }.message-details pre { max-height:210px; margin:.6rem 0 0; overflow:auto; border-radius:8px; padding:.65rem; color:#305b54; background:#fff; font-size:.8rem; line-height:1.5; white-space:pre-wrap; }.message-loading { display:flex; gap:.55rem; justify-content:center; align-items:center; min-height:160px; color:#69817c; font-size:.9rem; }.small-spinner { width:.8rem; height:.8rem; border:2px solid #c6e2da; border-top-color:#187c70; border-radius:50%; animation:spin .75s linear infinite; }@keyframes spin { to { transform:rotate(360deg); } }.chat-empty-state { display:grid; max-width:590px; gap:.75rem; place-self:center; place-items:center; padding:2rem; color:#68807b; text-align:center; }.empty-icon { display:grid; width:3.5rem; height:3.5rem; place-items:center; border-radius:18px; color:#16776d; background:#e1f4ee; font-size:1.65rem; }.chat-empty-state h3 { margin:0; color:#244b46; font-size:1.24rem; }.chat-empty-state p { margin:0; line-height:1.65; font-size:.92rem; }.chat-empty-state>button { display:flex; gap:.45rem; align-items:center; border:0; border-radius:10px; padding:.64rem .8rem; color:#fff; background:#167b70; font-size:.88rem; font-weight:800; }.suggestion-row { display:flex; flex-wrap:wrap; justify-content:center; gap:.45rem; margin-top:.2rem; }.suggestion-row button { border:1px solid #cce3dd; border-radius:999px; padding:.5rem .7rem; color:#286c63; background:#fff; font-size:.8rem; }.composer { display:grid; gap:.65rem; padding-top:1rem; border-top:1px solid #e2ece9; }.mode-rail { display:grid; grid-template-columns:minmax(10rem,.75fr) minmax(0,2.25fr); gap:.75rem; align-items:center; }.mode-copy { display:grid; gap:.18rem; }.mode-copy span { color:#426860; font-size:.84rem; font-weight:850; }.mode-copy small { color:#7b918c; font-size:.78rem; line-height:1.4; }.mode-options { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:.4rem; }.mode-chip { display:grid; gap:.12rem; border:1px solid #d9e8e4; border-radius:10px; padding:.6rem .7rem; color:#53736e; background:#fff; text-align:left; box-shadow:none; }.mode-chip strong { font-size:.86rem; }.mode-chip small { overflow:hidden; color:#80948f; font-size:.74rem; text-overflow:ellipsis; white-space:nowrap; }.mode-chip.selected { border-color:#1b8779; color:#0d635b; background:#e4f5f0; box-shadow:inset 0 0 0 1px rgb(27 135 121 / 10%); }.mode-chip.selected small { color:#327a70; }.knowledge-strip { display:grid; gap:.55rem; padding:.78rem .88rem; border:1px solid #d6e8e2; border-radius:13px; background:#f5fbf9; }.knowledge-strip>div:first-child { display:flex; flex-wrap:wrap; gap:.45rem; align-items:baseline; }.knowledge-strip strong { color:#365f59; font-size:.86rem; }.knowledge-strip small { color:#738a85; font-size:.78rem; }.knowledge-options { display:flex; flex-wrap:wrap; gap:.45rem; }.knowledge-chip { border:1px solid #bfdcd4; border-radius:999px; padding:.46rem .7rem; color:#376c63; background:#fff; font-size:.82rem; box-shadow:none; }.knowledge-chip.selected { border-color:#168274; color:#fff; background:#168274; }.knowledge-error { margin:0; color:#ac3024; font-size:.84rem; }.knowledge-empty { display:flex; gap:.42rem; align-items:center; color:#9d5500; font-size:.84rem; line-height:1.45; }.input-frame { padding:.78rem; border:1px solid #cfdfda; border-radius:16px; background:#fff; box-shadow:0 5px 14px rgb(28 68 62 / 4%); }.input-frame textarea { display:block; min-height:96px; padding:.6rem; border:0; border-radius:9px; background:#fff; box-shadow:none; font-size:.98rem; }.input-frame textarea:focus { box-shadow:none; }.composer-actions { display:flex; gap:.5rem; align-items:center; padding:.4rem .2rem .08rem; }.composer-actions small { margin-right:auto; color:#82958f; font-size:.76rem; }.composer-actions kbd { border:1px solid #d1e0db; border-bottom-width:2px; border-radius:4px; padding:.06rem .22rem; color:#657b76; background:#f7faf9; font-size:.7rem; }.composer-actions small span { padding:0 .2rem; }.stop-button,.send-button { border:0; border-radius:9px; padding:.58rem .78rem; font-size:.84rem; font-weight:800; }.stop-button { color:#52716b; background:#ecf3f0; }.send-button { color:#fff; background:linear-gradient(135deg,#17877a,#11675f); box-shadow:0 7px 13px rgb(20 105 96 / 17%); }
@media (max-width:980px) { .chat-shell { grid-template-columns:230px minmax(0,1fr); }.mode-rail { grid-template-columns:1fr; }.mode-copy { display:flex; gap:.45rem; align-items:baseline; }.message { width:min(88%,800px); } }.conversation-list { min-height:0; }@media (max-width:720px) { .chat-shell { height:auto; min-height:calc(100dvh - 145px); grid-template-columns:1fr; }.conversation-list { max-height:240px; border-right:0; border-bottom:1px solid #dce9e5; }.conversation-heading { padding-bottom:.5rem; }.conversation-empty { margin:.25rem 0; padding:.5rem; place-items:start; text-align:left; }.conversation-empty p { display:none; }.conversation-items { display:flex; flex:none; min-height:auto; margin-top:.55rem; overflow:auto; }.conversation-row { flex:0 0 195px; }.chat-main { padding:1rem; }.chat-header { padding-bottom:.75rem; }.messages { min-height:340px; padding:1rem 0; }.message { width:min(94%,800px); }.mode-options { grid-template-columns:1fr; }.mode-chip { grid-template-columns:6.5rem 1fr; align-items:center; }.composer-actions { flex-wrap:wrap; }.composer-actions small { flex-basis:100%; }.chat-empty-state { padding:1rem; } }
</style>
