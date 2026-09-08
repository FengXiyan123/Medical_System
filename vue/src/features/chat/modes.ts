export type ExecutionModeId = "AUTO_KB" | "MANUAL_KB" | "AGENT";

export interface ExecutionMode {
  id: ExecutionModeId;
  label: string;
  description: string;
}

export const executionModes: readonly ExecutionMode[] = [
  {
    id: "AUTO_KB",
    label: "自动知识",
    description: "系统在用户有权访问的知识库中自动选择资料。"
  },
  {
    id: "MANUAL_KB",
    label: "自选知识",
    description: "仅检索用户本次明确选择的知识库。"
  },
  {
    id: "AGENT",
    label: "自由执行",
    description: "Agent 在授权范围内选择知识库和白名单工具。"
  }
];
