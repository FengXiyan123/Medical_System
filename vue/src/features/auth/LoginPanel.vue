<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { Lock, UserFilled } from "@element-plus/icons-vue";

type LoginRole = "ADMIN" | "USER";

const props = withDefaults(defineProps<{
  pending?: boolean;
  error?: string;
}>(), { pending: false, error: "" });

const emit = defineEmits<{ login: [username: string, password: string] }>();
const role = ref<LoginRole>("USER");
const username = ref("user");
const password = ref("user");
const roleLabel = computed(() => role.value === "ADMIN" ? "管理员" : "普通用户");

function selectRole(next: LoginRole) {
  role.value = next;
  username.value = next === "ADMIN" ? "admin" : "user";
  password.value = next === "ADMIN" ? "admin" : "user";
}

function submit() {
  emit("login", username.value.trim(), password.value);
}

watch(() => props.error, error => {
  if (!error) return;
  password.value = "";
});
</script>

<template>
  <section class="login-card" aria-labelledby="login-title">
    <div class="login-brand">
      <div class="brand-mark" aria-hidden="true">＋</div>
      <div><p>MEDICAL INTELLIGENCE</p><h1 id="login-title">进入医疗智能问答系统</h1></div>
    </div>

    <p class="login-lead">选择身份后登录。系统用于医疗知识教学与 Agent 工作流演示，不用于诊断或处方。</p>

    <div class="role-switch" role="tablist" aria-label="登录身份">
      <button type="button" role="tab" :aria-selected="role === 'USER'" :class="{ active: role === 'USER' }" @click="selectRole('USER')">
        <el-icon><UserFilled /></el-icon>
        <span><strong>普通用户登录</strong><small>健康知识问答与个人会话</small></span>
      </button>
      <button type="button" role="tab" :aria-selected="role === 'ADMIN'" :class="{ active: role === 'ADMIN' }" @click="selectRole('ADMIN')">
        <el-icon><Lock /></el-icon>
        <span><strong>管理员登录</strong><small>知识库、调用链与用户管理</small></span>
      </button>
    </div>

    <form class="login-form" @submit.prevent="submit">
      <label>用户名<input v-model="username" autocomplete="username" maxlength="64" required /></label>
      <label>密码<input v-model="password" autocomplete="current-password" maxlength="256" required type="password" /></label>
      <p class="demo-account">当前为{{ roleLabel }}演示入口；演示账号：admin / admin；普通用户：user / user</p>
      <p v-if="error" class="form-message error" role="alert">{{ error }}</p>
      <p v-else class="form-message" aria-live="polite">{{ pending ? "正在验证账号与权限…" : "登录后会自动恢复可访问的知识库范围。" }}</p>
      <button class="login-submit" type="submit" :disabled="pending">
        {{ pending ? "正在登录…" : `以${roleLabel}身份登录` }}
      </button>
    </form>
  </section>
</template>

<style scoped>
.login-card { width:min(100%, 740px); padding:clamp(1.45rem, 4vw, 2.8rem); border:1px solid rgb(255 255 255 / 82%); border-radius:28px; background:rgb(255 255 255 / 92%); box-shadow:0 28px 75px rgb(17 67 59 / 16%); backdrop-filter:blur(14px); }
.login-brand { display:flex; gap:1rem; align-items:center; }.brand-mark { display:grid; width:3rem; height:3rem; place-items:center; border-radius:14px; color:#fff; background:linear-gradient(135deg,#159081,#0d4b48); font-size:2rem; font-weight:300; box-shadow:0 12px 22px rgb(13 75 72 / 22%); }.login-brand p { margin:0 0 .28rem; color:#347e73; font-size:.72rem; font-weight:800; letter-spacing:.11em; }.login-brand h1 { margin:0; color:#173937; font-size:clamp(1.55rem,3vw,2.25rem); letter-spacing:-.04em; }.login-lead { max-width:56ch; margin:1.5rem 0; color:#607873; line-height:1.72; }
.role-switch { display:grid; grid-template-columns:1fr 1fr; gap:.7rem; margin-bottom:1.15rem; }.role-switch button { display:flex; min-height:96px; gap:.65rem; align-items:flex-start; padding:1rem; border:1px solid #dceae5; border-radius:16px; color:#41605c; background:#f9fcfb; text-align:left; box-shadow:none; transition:.18s ease; }.role-switch button:hover { border-color:#8cbeb4; transform:translateY(-1px); }.role-switch button.active { border-color:#168274; color:#124b46; background:#e3f5ef; box-shadow:inset 0 0 0 1px rgb(22 130 116 / 16%); }.role-switch :deep(.el-icon) { margin-top:.1rem; color:#168274; font-size:1.25rem; }.role-switch span { display:grid; gap:.25rem; }.role-switch strong { font-size:.93rem; }.role-switch small { color:#708883; font-size:.76rem; line-height:1.45; }
.login-form { display:grid; gap:.8rem; }.login-form label { display:grid; gap:.4rem; color:#365953; font-size:.82rem; font-weight:750; }.login-form input { min-height:48px; }.demo-account { margin:0; color:#6c837e; font-size:.78rem; }.form-message { min-height:1.3rem; margin:0; color:#68817c; font-size:.8rem; }.login-submit { min-height:48px; border:0; border-radius:12px; color:white; background:linear-gradient(135deg,#178679,#0d5b55); font-weight:800; box-shadow:0 12px 22px rgb(13 91 85 / 19%); }.login-submit:hover:not(:disabled) { background:linear-gradient(135deg,#0f776c,#084b47); transform:translateY(-1px); }.login-submit:disabled { opacity:.64; }
@media (max-width:560px) { .role-switch { grid-template-columns:1fr; }.login-card { border-radius:22px; }.role-switch button { min-height:78px; } }
</style>
