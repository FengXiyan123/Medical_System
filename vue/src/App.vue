<script setup lang="ts">
import { computed, onMounted, ref, watch } from "vue";
import { RouterLink, useRoute, useRouter } from "vue-router";
import { Grid, Monitor, SwitchButton } from "@element-plus/icons-vue";

import LoginPanel from "./features/auth/LoginPanel.vue";
import { AuthApi } from "./features/auth/api";
import { createAuthState } from "./features/auth/session";
import { createChatModeState } from "./features/chat/useChatMode";
import ChatWorkspace from "./features/chat/ChatWorkspace.vue";
import { ChatApi, type AccessibleKnowledgeBase } from "./features/chat/api";
import AdminWorkspace from "./features/admin/AdminWorkspace.vue";

const { mode, selectMode, selectedKnowledgeBaseIds, setKnowledgeBaseIds } = createChatModeState();
const auth = createAuthState(window.sessionStorage);
const route = useRoute();
const router = useRouter();
const authApi = new AuthApi();

const currentUser = computed(() => auth.session.value?.user ?? null);
const activeSession = computed(() => auth.session.value);
const isChatRoute = computed(() => route.name === "chat");
const isAdminRoute = computed(() => route.name === "admin");
const pageName = computed(() => isAdminRoute.value ? "管理后台" : "问答工作台");
const loginError = ref("");
const loginPending = ref(false);
const restoringSession = ref(true);
const accessibleKnowledgeBases = ref<AccessibleKnowledgeBase[]>([]);
const knowledgeDirectoryError = ref("");

async function loadKnowledgeDirectory(session: NonNullable<typeof auth.session.value>) {
  accessibleKnowledgeBases.value = [];
  knowledgeDirectoryError.value = "";
  try {
    const knowledgeBases = await new ChatApi(session).listAccessibleKnowledgeBases();
    accessibleKnowledgeBases.value = knowledgeBases.filter(item => item.status === "PUBLISHED");
    setKnowledgeBaseIds(selectedKnowledgeBaseIds.value.filter(id => accessibleKnowledgeBases.value.some(item => item.id === id)));
  } catch (error) {
    knowledgeDirectoryError.value = error instanceof Error ? error.message : "无法加载可用知识库";
  }
}

async function login(username: string, password: string) {
  loginError.value = "";
  loginPending.value = true;
  try {
    auth.setSession(await authApi.login(username, password));
    await router.replace("/chat");
  } catch (error) {
    loginError.value = error instanceof Error ? error.message : "登录失败，请检查用户名和密码。";
  } finally {
    loginPending.value = false;
  }
}

async function logout() {
  const refreshToken = auth.session.value?.refreshToken;
  auth.logout();
  accessibleKnowledgeBases.value = [];
  if (refreshToken) await authApi.logout(refreshToken).catch(() => undefined);
  await router.replace("/login");
}

onMounted(async () => {
  const refreshToken = auth.session.value?.refreshToken;
  if (!refreshToken) {
    restoringSession.value = false;
    return;
  }
  try {
    auth.setSession(await authApi.refresh(refreshToken));
  } catch {
    auth.logout();
  } finally {
    restoringSession.value = false;
  }
});

watch(activeSession, async session => {
  accessibleKnowledgeBases.value = [];
  knowledgeDirectoryError.value = "";
  if (restoringSession.value) return;
  if (!session) {
    if (route.name !== "login") await router.replace("/login");
    return;
  }
  await loadKnowledgeDirectory(session);
  if (route.name === "login") await router.replace("/chat");
  if (route.name === "admin" && session.user.role !== "ADMIN") await router.replace("/chat");
});

watch(restoringSession, async restoring => {
  if (restoring) return;
  if (!auth.session.value) {
    if (route.name !== "login") await router.replace("/login");
    return;
  }
  await loadKnowledgeDirectory(auth.session.value);
  if (route.name === "login") await router.replace("/chat");
}, { immediate: true });
</script>

<template>
  <main v-if="!activeSession && !restoringSession" class="auth-page">
    <div class="auth-backdrop" aria-hidden="true"><span></span><span></span><span></span></div>
    <LoginPanel :pending="loginPending" :error="loginError" @login="login" />
    <p class="auth-footer">教学／演示环境 · 所有会话与调用轨迹均在系统内留存</p>
  </main>

  <main v-else class="app-shell">
    <section v-if="restoringSession" class="restore-screen" aria-live="polite">
      <span class="pulse-dot" aria-hidden="true"></span>
      <div><strong>正在恢复工作台</strong><p>正在验证你的登录状态和知识库权限。</p></div>
    </section>

    <template v-else-if="activeSession">
      <header class="app-topbar">
        <RouterLink class="brand-lockup" to="/chat" aria-label="医疗智能问答系统首页">
          <span class="brand-symbol">＋</span>
          <span><strong>医疗智能问答系统</strong><small>{{ pageName }}</small></span>
        </RouterLink>
        <nav class="workspace-nav" aria-label="主导航">
          <RouterLink to="/chat"><el-icon><Monitor /></el-icon> 问答工作台</RouterLink>
          <RouterLink v-if="currentUser?.role === 'ADMIN'" to="/admin"><el-icon><Grid /></el-icon> 管理后台</RouterLink>
        </nav>
        <div class="account-menu">
          <span class="account-avatar" aria-hidden="true">{{ currentUser?.username.slice(0, 1).toUpperCase() }}</span>
          <span class="account-name"><strong>{{ currentUser?.username }}</strong><small>{{ currentUser?.role === "ADMIN" ? "管理员" : "普通用户" }}</small></span>
          <button class="sign-out" type="button" @click="logout"><el-icon><SwitchButton /></el-icon><span>退出</span></button>
        </div>
      </header>

      <section class="app-content">
        <AdminWorkspace v-if="isAdminRoute && currentUser?.role === 'ADMIN'" :session="activeSession" />
        <ChatWorkspace
          v-else-if="isChatRoute"
          :session="activeSession"
          :mode="mode"
          :selected-knowledge-base-ids="selectedKnowledgeBaseIds"
          :knowledge-bases="accessibleKnowledgeBases"
          :knowledge-error="knowledgeDirectoryError"
          @mode-change="selectMode"
          @knowledge-base-ids-change="setKnowledgeBaseIds"
        />
        <section v-else class="route-state"><h1>页面正在准备</h1><p>正在返回问答工作台。</p></section>
      </section>
    </template>
  </main>
</template>

<style scoped>
.auth-page { display:grid; min-height:100vh; place-items:center; position:relative; overflow:hidden; padding:1.25rem; }.auth-backdrop { position:absolute; inset:0; overflow:hidden; pointer-events:none; }.auth-backdrop span { position:absolute; border:1px solid rgb(35 132 116 / 11%); border-radius:999px; }.auth-backdrop span:nth-child(1) { width:48rem; height:48rem; top:-30rem; left:-15rem; background:radial-gradient(circle at 65% 67%,rgb(76 179 157 / 21%),transparent 62%); }.auth-backdrop span:nth-child(2) { width:38rem; height:38rem; right:-16rem; bottom:-18rem; background:radial-gradient(circle at 35% 35%,rgb(40 140 122 / 18%),transparent 62%); }.auth-backdrop span:nth-child(3) { width:20rem; height:20rem; top:18%; right:10%; border-style:dashed; }.auth-page :deep(.login-card) { position:relative; z-index:1; }.auth-footer { position:absolute; z-index:1; bottom:1.5rem; margin:0; color:#67817a; font-size:.78rem; }
 .app-shell { display:flex; min-height:100dvh; height:100dvh; flex-direction:column; overflow:hidden; padding:clamp(.55rem,1vw,1rem); }.app-topbar { display:grid; flex:0 0 auto; grid-template-columns:minmax(14rem,1fr) auto minmax(14rem,1fr); gap:1rem; align-items:center; width:100%; margin:0 0 .8rem; padding:.78rem 1rem; border:1px solid rgb(255 255 255 / 66%); border-radius:18px; background:rgb(255 255 255 / 75%); box-shadow:0 12px 35px rgb(19 64 58 / 7%); backdrop-filter:blur(18px); }.brand-lockup { display:flex; gap:.65rem; align-items:center; color:#173937; text-decoration:none; }.brand-lockup>span:last-child { display:grid; gap:.1rem; }.brand-lockup strong { font-size:.95rem; letter-spacing:-.02em; }.brand-lockup small { color:#68817b; font-size:.7rem; }.brand-symbol { display:grid; width:2.25rem; height:2.25rem; place-items:center; border-radius:10px; color:#fff; background:linear-gradient(135deg,#168779,#0b504c); font-size:1.5rem; box-shadow:0 7px 14px rgb(12 87 80 / 19%); }.workspace-nav { display:flex; gap:.35rem; justify-content:center; }.workspace-nav a,.sign-out { display:flex; gap:.35rem; align-items:center; padding:.52rem .7rem; border:1px solid transparent; border-radius:10px; color:#52716c; background:transparent; text-decoration:none; font-size:.82rem; font-weight:750; box-shadow:none; }.workspace-nav a.router-link-active { color:#0c5c54; background:#e3f5ef; }.workspace-nav a:hover,.sign-out:hover { color:#0c5c54; background:#eff8f5; }.account-menu { display:flex; justify-content:flex-end; gap:.5rem; align-items:center; }.account-avatar { display:grid; width:2rem; height:2rem; place-items:center; border-radius:50%; color:#eafff9; background:#176e65; font-size:.8rem; font-weight:800; }.account-name { display:grid; gap:.08rem; }.account-name strong { font-size:.8rem; }.account-name small { color:#708782; font-size:.69rem; }.sign-out { margin-left:.25rem; }.app-content { width:100%; min-height:0; flex:1; }.restore-screen,.route-state { display:flex; gap:1rem; align-items:center; max-width:620px; margin:16vh auto; padding:1.35rem; border:1px solid #dbe8e3; border-radius:18px; background:#fff; box-shadow:0 16px 36px rgb(25 67 61 / 8%); }.restore-screen p,.route-state p { margin:.35rem 0 0; color:#657d78; }.pulse-dot { width:.85rem; height:.85rem; border-radius:50%; background:#168274; box-shadow:0 0 0 0 rgb(22 130 116 / 35%); animation:pulse 1.5s infinite; }@keyframes pulse { 70% { box-shadow:0 0 0 .65rem transparent; }100% { box-shadow:0 0 0 0 transparent; } }
 @media (max-width:820px) { .app-topbar { grid-template-columns:1fr auto; }.workspace-nav { grid-column:1/-1; grid-row:2; justify-content:flex-start; padding-top:.45rem; border-top:1px solid #e5eeeb; }.account-name { display:none; } }.auth-footer { position:static; margin-top:1rem; text-align:center; }.auth-page { align-content:center; }.auth-page :deep(.login-card) { margin-top:auto; }.auth-footer { z-index:1; }
 @media (max-width:720px) { .app-shell { height:auto; min-height:100dvh; overflow:visible; }.app-content { flex:auto; } }
@media (max-width:500px) { .app-shell { padding:.55rem; }.app-topbar { padding:.72rem; border-radius:14px; }.brand-lockup strong { font-size:.84rem; }.workspace-nav a { padding:.48rem .5rem; font-size:.76rem; }.sign-out span { display:none; }.account-menu { gap:.3rem; } }
</style>
