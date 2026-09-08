# 医疗智能问答系统

这是一个用 Java + Python + Vue Agent/RAG 写的的练手项目。系统提供自动知识、自选知识和自由执行三种模式，并为回答保留来源、执行轨迹和 Token 用量记录。演示资料均为合成资料；本项目不用于诊断、处方或真实患者数据处理。

## 目录

| 目录 | 责任 |
| --- | --- |
| `agent-gateway/` | Spring Boot 网关、认证、会话、知识管理、管理端 API、审计 |
| `agent-core/` | FastAPI、RAG/Agent、切块、检索、模型与内部执行 |
| `vue/` | Vue 3 问答与管理端界面 |
| `infra/` | PostgreSQL/pgvector、Redis、Nginx 配置 |
| `docs/` | 架构、实施计划、演示和验证材料 |
| `scripts/` | 数据集校验、运行时 smoke、合成资料导入、全量验证 |

详细设计见 [docs/README.md](docs/README.md)，最终演示步骤见 [docs/demo-script.md](docs/demo-script.md)。

## 运行要求

- Docker Compose v2
- JDK 21（网关构建）
- Python 3.12 与 `uv`（Agent Core 校验）
- Node.js 20+ 与 npm（Vue 构建）
- `curl`、`python3`（脚本）

默认使用确定性 `mock` 模型。只有设置 `MODEL_MODE=real`、百炼地域、业务空间 ID 和 API Key 后才会尝试真实 `qwen3.6-flash`；真实调用不是常规验证的一部分。

## 本地启动

```bash
# 首次使用时复制模板；已有 .env 时不要覆盖它。
cp .env.example .env
# 编辑 .env：至少设置两个 32 字节以上的随机密钥，勿提交 .env
docker compose up -d --build
bash scripts/smoke.sh
```

浏览器入口默认是 `http://127.0.0.1:5173`。网关、Agent Core 和前端分别只绑定本机端口，端口可在 `.env` 中调整。

上传文件会由 Redis Stream 交给 Agent Core：解析、生成切块、写入 PostgreSQL/pgvector，并回调网关创建可发布的生成版本。默认本地路径为项目根目录的 `data/uploads`，网关和 Agent Core 会自动共用它；如设置 `MEDICAL_STORAGE_ROOT`，两端必须设置为同一个绝对路径。若 Redis 使用了 `--requirepass`，还必须在 `.env` 设置 `REDIS_PASSWORD`，否则文档处理事件无法被消费。

`smoke.sh` 只检查三个 HTTP 服务是否可达，不创建数据、不跑 RAG、不调用模型。

### 初始化管理员与合成资料

当前网关没有公开注册接口。教学／演示环境可在 `.env` 中设置 `DEMO_BOOTSTRAP_ENABLED=true`，由应用启动时幂等创建演示账户。默认演示账户为 `admin` / `admin`（管理员）和 `user` / `user`（普通用户）；仅限本地演示，部署真实环境前必须关闭该开关并使用独立的强密码。

取得管理员登录生成的短期 Access Token 后，可以导入合成资料：

```bash
export DEMO_ADMIN_ACCESS_TOKEN='粘贴管理员短期 Access Token'
bash scripts/seed-demo.sh
```

脚本会创建或复用名为“`M6 合成评测资料`”的知识库并上传 `docs/demo-data/` 中四份 Markdown。它明确**不会**声称导入已完成、切块已构建或文档已发布；随后在管理端完成“解析 → 切块预览 → 构建 → 发布”。

`MODEL_MODE=mock` 使用确定性演示模型和本地确定性向量；`MODEL_MODE=real` 会调用已配置的 Qwen3.6-Flash，并使用 DashScope `text-embedding-v4` 生成 1024 维检索向量。旧的布尔写法 `MODEL_MODE=true` / `false` 会分别兼容为 `real` / `mock`，但建议改为明确的枚举值。

## 验证

执行不启动容器的代码级校验：

```bash
bash scripts/verify.sh
```

脚本校验合成评测集、Compose 配置、Python Ruff/单元测试、Vue 类型检查/单元测试/构建和 Java Maven verify。环境中存在可运行服务时，可附加 HTTP smoke：

```bash
RUN_RUNTIME_CHECKS=1 bash scripts/verify.sh
```

结构化评测集位于 [docs/evaluation/synthetic-evaluation-v1.json](docs/evaluation/synthetic-evaluation-v1.json)，可独立执行：

```bash
python3 scripts/validate_eval_dataset.py
```

`vue/tests/e2e/` 目前只提供可执行前的浏览器验收约定，没有锁定 Playwright/Cypress 与浏览器依赖。因此没有任何浏览器 E2E 结果可以被视为已通过。

## 配置和安全边界

- 密钥只通过环境变量/Secret 注入；不要提交 `.env`、Access Token 或百炼 API Key。
- `GATEWAY_ACCESS_TOKEN_SECRET`、`GATEWAY_SERVICE_TOKEN_SECRET` 均须使用不同的随机值。
- 真实模型模式必须明确配置；没有凭证时应继续使用 mock，而不是伪造 Token、价格或模型结果。
- 管理员 API 与用户会话均需服务端鉴权；前端隐藏入口不能替代权限控制。

已验证项、未验证的真实依赖，以及 AC-01 至 AC-26 的证据状态见 [docs/verification-report.md](docs/verification-report.md)。
