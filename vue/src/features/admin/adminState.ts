import { computed, ref } from "vue";

import type { UserRole } from "../auth/session";

export type AdminView = "KNOWLEDGE" | "TRACES" | "USAGE" | "USERS" | "AUDIT" | "POLICIES";

export const adminViews: { id: AdminView; label: string }[] = [
  { id: "KNOWLEDGE", label: "知识库与切块" },
  { id: "TRACES", label: "调用链" },
  { id: "USAGE", label: "用量统计" },
  { id: "USERS", label: "用户管理" },
  { id: "AUDIT", label: "操作审计" },
  { id: "POLICIES", label: "模型与策略" }
];

/** Keeps management UI state separate from server-side role enforcement. */
export function createAdminState() {
  const role = ref<UserRole | null>(null);
  const activeView = ref<AdminView>("KNOWLEDGE");
  const visible = computed(() => role.value === "ADMIN");

  function open(nextRole: UserRole) {
    role.value = nextRole;
    if (nextRole !== "ADMIN") activeView.value = "TRACES";
  }

  function select(view: AdminView) {
    if (visible.value) activeView.value = view;
  }

  return { activeView, visible, open, select };
}
