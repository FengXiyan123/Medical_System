<script setup lang="ts">
import { ref } from "vue";
import type { AuthSession } from "../auth/session";
import { ChatApi } from "./api";
const props = defineProps<{ session: AuthSession; answerId: string; disabled?: boolean }>();
const rating = ref<"UP" | "DOWN" | null>(null); const reason = ref(""); const sending = ref(false); const message = ref("");
async function submit(value: "UP" | "DOWN") { if (props.disabled || sending.value) return; rating.value = value; sending.value = true; message.value = ""; try { await new ChatApi(props.session).submitFeedback(props.answerId, value, reason.value); message.value = "已保存反馈"; } catch (cause) { message.value = cause instanceof Error ? cause.message : "反馈保存失败"; } finally { sending.value = false; } }
</script>
<template><section class="feedback" aria-label="回答反馈"><div><button type="button" :disabled="disabled || sending" :class="{ selected: rating === 'UP' }" @click="submit('UP')">有帮助</button><button type="button" :disabled="disabled || sending" :class="{ selected: rating === 'DOWN' }" @click="submit('DOWN')">需改进</button></div><label>原因（可选）<input v-model="reason" maxlength="500" :disabled="disabled || sending" placeholder="例如：引用不相关" /></label><small v-if="message" role="status">{{ message }}</small></section></template>
<style scoped>.feedback{display:grid;gap:.4rem;margin-top:.65rem}.feedback div{display:flex;gap:.4rem}.feedback button,.feedback input{border:1px solid #9ebfba;border-radius:.4rem;padding:.35rem .5rem;background:#fff}.feedback button.selected{border-color:#20756d;background:#e8f6f2}.feedback label{display:grid;gap:.2rem;font-size:.8rem}.feedback small{color:#277871}</style>
