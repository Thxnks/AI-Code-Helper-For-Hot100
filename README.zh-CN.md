# AI Code Helper — Hot100 算法刷题 Agent

[English](./README.md)

Spring Boot + Vue 全栈项目，在传统后端业务系统之上自研了一套 ReAct Agent Runtime 作为核心编排引擎，面向算法学习辅导场景。项目为后端实习面试设计，重点展示系统架构能力、框架集成取舍以及自研 LLM 编排层的工程深度——区别于常见的 API 薄封装。

## 与常规 AI 项目的区别

多数简历中的 AI 项目停留在"调用大模型 API + 前端展示"的薄封装层面。本项目的核心差异在于：**Agent Runtime 是自主实现的**。ReAct 多轮循环、工具权限分级、计划书写与校验、三层上下文压缩、工具三级恢复、工具幂等保护、子代理隔离、执行过程 SSE 流式追踪——这些编排层代码全部从零构建，LangChain4j 仅负责底层的模型通信。

## 技术亮点

### Agent Runtime（自研 ReAct 引擎）

- **自研 ReAct 循环**：`模型推理 → 工具调用 → 观察结果 → 下一轮决策 → 最终答案`，单次任务最多 8 轮自主执行。LangChain4j 在此仅作为 ChatModel 的适配层，所有循环控制、状态管理和决策解析均由自研代码实现。
- **计划书写与校验**：长链路 Agent 执行前先要求模型输出结构化 JSON 计划，校验步骤顺序、工具是否存在、是否包含至少一个工具型数据获取步骤；校验失败时把问题反馈给模型并要求重写计划，校验通过后将计划渲染回后续提示词。
- **三层渐进式上下文压缩**（参考 Claude Code 策略）：Tier 1 Snip — 按评分规则移除低价值历史消息，保留原始目标与最近对话；Tier 2 Microcompact — 对大体积 JSON 工具返回做字段级裁剪，仅保留关键信息；Tier 3 Autocompact — 调用模型生成结构化语义摘要（`goal`、`done`、`findings`、`remaining`），替换全部消息列表。前两级为纯内存操作，仅在仍不满足限制时进入网络调用级压缩。
- **四级工具权限门**：`READ`、`WRITE`、`EXTERNAL`、`SENSITIVE`。权限拒绝时返回结构化 `tool_result` 错误信息，而非抛出异常——模型感知到工具被拒绝后可自行调整策略，而非因异常中断循环。
- **工具幂等保护**：基于 Redis 记录工具名 + 规范化入参的 SHA-256 哈希，10 分钟 TTL 内重复工具调用直接复用缓存结果，降低重试过程中的重复写入和重复外部操作风险。
- **工具三级恢复策略**：瞬时错误自动重试，参数或模型输出错误以结构化提示交还模型修正，语义级错误触发 replan 路径。未注册工具、JSON 解析失败、工具执行异常、超过最大轮数都会生成类型化 recovery decision。
- **事件钩子体系**：模型轮次、工具调用、权限拒绝、上下文压缩、计划校验、重新规划、异常恢复等关键节点均触发 Hook 事件。Observer 监听这些事件完成 Step 持久化、心跳上报和 SSE 推送——业务逻辑与循环内核完全解耦。

### AI 集成

- **MCP 双通道分流**：同一套 MCP 外部工具（如 DashScope WebSearch），同时服务于普通聊天（经 LangChain4j 原生 `McpToolProvider`）和 Agent 工具注册表（动态注册为 `mcp_*` 工具，归属 `EXTERNAL` 权限）。通过 `ObjectProvider<McpClient>` 实现条件注入，未启用 MCP 时外部工具静默跳过，不影响核心流程。
- **混合可解释 RAG**：加载 Hot100 markdown 与 JSON 资源，按章节切分并补充题目元数据（slug、难度、标签、解题模式），再通过 `HybridRanker` 合并 Redis 向量相似度和本地关键词分数。返回结果包含来源文件、匹配分数、命中词和命中窗口，整个过程可追溯，便于调试和测试。
- **Agent 步骤级 SSE 流式推送**：区别于聊天场景的 token 级逐字流式（LangChain4j 原生支持），本模块推送的是 Agent 执行事件（`model_turn`、`tool_result`、`tool_error`、`finish`）。基于 Reactor `Sinks.Many` 桥接阻塞式 Agent 循环与响应式 SSE 流，前端可实时展示执行过程而非等待最终结果。

### 长期记忆系统

- 五种记忆类型：`USER_PREFERENCE`（用户偏好）、`WEAKNESS`（薄弱知识点）、`WRONG_ANSWER`（错题模式）、`NEXT_ACTION`（下一步行动）、`NOTE`（普通笔记）。
- 召回排序：对查询文本分词后遍历每条记忆的 type、scope、subject、content、source 字段，命中次数加权 + importance 权重双排序，非简单 `LIKE` 查询。
- 进度更新时自动触发记忆写入：错因、薄弱知识点、下一步行动等信息通过 `rememberProgress` 方法自动持久化到记忆库。

### 后端工程基础

- Spring Boot 3.5 / Java 21 / Spring Security / JWT（access + refresh token）
- Spring Data JPA + Flyway 数据库迁移 / Redis 缓存（按类型独立 TTL，支持本地 fallback）
- Docker Compose 一键部署 / Maven Wrapper 可复现构建
- `@Scheduled` 心跳看门狗：每 30 秒扫描运行中槽位的心跳时间，超过 5 分钟无心跳自动标记 FAILED

## 整体架构

```text
Frontend (Vue 3)
  -> REST / SSE APIs
  -> Spring Boot Backend
       -> Auth / Chat / Hot100 / Agent Controllers
       -> Domain Services
       -> Agent Runtime
            -> AgentPromptBuilder
            -> Model turn
            -> tool_use JSON
            -> AgentPermissionGate
            -> AgentToolRegistry
            -> Java tool handler
            -> tool_result
            -> next model turn or final_answer
       -> JPA Repositories
  -> MySQL / Redis
```

模型可以决定调用哪个已注册工具，但真正的执行权在后端。每个工具都有名称、描述、权限等级和 Java handler，因此模型负责推理，系统负责受控执行。

## Agent Runtime 数据模型

Agent 模块将”用户目标”和”执行尝试”拆开，每一步都有迹可循：

```text
AgentTask         — 用户目标（goal, status, finalAnswer）
  └─ RuntimeSlot  — 一次执行尝试（attempt, executorId, heartbeatAt）
       └─ AgentStep — 单步模型调用或工具执行（stepOrder, toolName, latencyMs）
```

一个任务可对应多次执行尝试（重试场景），每次尝试产生多个步骤。该设计为任务重试、执行过程排查和后续执行器故障切换提供了清晰的数据边界。

## 后台 Agent 工具

Hot100 Agent 可以启动聚焦型后台分析任务，避免阻塞当前 ReAct 主循环：

- `background_run`：启动一个后台分析任务，并立即返回由 runtime slot 承载的 `task_id`。
- `background_check`：查询指定后台任务的状态、阶段、进度、错误信息和输出结果。
- 后台任务通过 `RuntimeTaskService` 运行在 Spring executor 上，并复用 sub-agent 机制完成聚焦分析。
- 后台任务完成、失败或超时时会写入 `BackgroundNotification`。主 Agent loop 在每轮模型调用前 drain 这些通知，并把它们作为新上下文注入对话。
- 看门狗依赖 runtime heartbeat 判断任务是否卡死，超过 5 分钟无心跳会把后台 slot 标记为 `FAILED`。

## Hot100 业务能力

- 按关键词、标签、难度筛选 Hot100 题目。
- 查看题目详情、解题模式、核心思路、复杂度、常见错误和 markdown 笔记。
- 保存学习进度、笔记、错因、薄弱知识点、AI 反馈和下一步行动。
- 维护错题本并生成错题分析。
- 根据做题记录计算薄弱标签和标签掌握度。
- 生成推荐题单和学习计划。
- 支持同步运行 Hot100 Agent、提交后台任务，或通过 SSE 实时查看 Agent 执行事件。
- 按任务和 runtime slot 查看模型 / 工具调用 trace。

## AI 与 RAG

`AgentKnowledgeService` 为 Agent 提供 `retrieveKnowledge` 工具。

当前检索链路：

- 加载 `src/main/resources/hot100/markdown/*.md`
- 加载 `src/main/resources/hot100/json/*.json`
- 按 markdown 标题切分章节级 chunk
- 为 chunk 补充题目元数据：slug、title、difficulty、tags、pattern、summary
- 可选将 docs 与 Hot100 笔记写入 LangChain4j embedding 检索链路
- 通过 `RedisEmbeddingStore` 持久化 embedding 向量，冷启动时可复用索引
- 在可用时同时查询内存 `ContentRetriever` 和 Redis 向量检索
- 使用 `HybridRanker` 做二次排序：向量分占 60%，归一化关键词分占 40%
- 返回可解释字段：source、slug、title、section、score、matchedTerms、content

这样项目在没有外部向量库时也有稳定可测的本地 RAG 能力；开启 RAG 后，也能使用 embedding / vector search 做语义检索。

## MCP 集成

项目已接入可选 MCP 能力，用于外部 WebSearch 等工具调用。MCP 同时服务于普通流式聊天和 Hot100 Agent 工具注册表两条链路。

- `McpConfig`：在 MCP 开启时创建 MCP client 和 `McpToolProvider`。
- `AiCodeHelperServiceFactory`：将 MCP tool provider 注册到 LangChain4j AI Service。
- `QwenMcpCapabilityService`：根据配置生成 MCP 能力提示。
- `AiController`：把 MCP 能力提示注入 SSE 流式聊天请求。
- `Hot100AgentToolRegistry`：当 MCP client 可用时，将 MCP 工具动态注册为 `mcp_*` Agent 工具，并标记为 `EXTERNAL` 权限。

MCP 默认关闭。如需开启 DashScope WebSearch MCP，在环境变量或 `.env` 中配置：

```env
DASHSCOPE_API_KEY=your_dashscope_api_key
APP_MCP_ENABLED=true
APP_MCP_SSE_URL=https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp
APP_MCP_WEB_SEARCH_TOOL_NAME=web_search
APP_MCP_WEB_SEARCH_QUERY_ARGUMENT=query
```

`APP_MCP_API_KEY` 可以不单独配置，因为后端会自动 fallback 到 `DASHSCOPE_API_KEY`：

```yaml
app.mcp.api-key: ${APP_MCP_API_KEY:${DASHSCOPE_API_KEY:}}
```

如果使用 Docker Compose 启动，根目录 `.env` 会被 Compose 读取。如果本地直接执行 `.\mvnw.cmd spring-boot:run`，PowerShell 不会自动读取 `.env`，需要先在当前 shell 中设置：

```powershell
$env:APP_MCP_ENABLED="true"
$env:APP_MCP_SSE_URL="https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp"
$env:APP_MCP_WEB_SEARCH_TOOL_NAME="web_search"
$env:APP_MCP_WEB_SEARCH_QUERY_ARGUMENT="query"
.\mvnw.cmd spring-boot:run
```

Agent 任务中的 MCP 外部工具受权限门控约束：请求需显式开启 `allowExternal`，否则归属 `EXTERNAL` 权限的工具将被拒绝执行。

## SSE 流式输出

两条流式链路：

| 端点 | 用途 | 粒度 |
|---|---|---|
| `GET /api/ai/chat` | AI 聊天 | token 级（逐字输出） |
| `POST /api/agent/hot100/run/stream` | Agent 执行 | 步骤级（model_turn, tool_result, tool_error, finish, error） |

Agent 事件携带 `type`、`turn`、`toolName`、`data`、`latencyMs`、`status` 字段。SSE 流通过 Reactor `Sinks.Many` 桥接阻塞式 Agent 循环与非阻塞响应式 SSE flux，前端可实时展示执行进度。

## 上下文压缩

三层策略——按代价递进，优先使用廉价操作：

| 层级 | 操作 | 开销 |
|---|---|---|
| TIER1_SNIP | 打分删除低价值旧消息，保留原始目标 + 最近几轮 | O(n)，纯内存 |
| TIER2_MICROCOMPACT | 压缩大 JSON 工具输出为关键字段，截断长文本 | O(n)，纯内存 |
| TIER3_AUTOCOMPACT | 调用模型生成结构化摘要（`goal`、`done`、`findings`、`remaining`），替换全部消息 | 网络 I/O + token 消耗 |

前两层直接操作 messages 列表，不调 `state.compact()`。只有 Tier 3 才调 `state.compact()`，清空全部消息，只保留原始目标 + 模型摘要。

## 计划、幂等与恢复

- **计划校验**：`AgentPlanValidator` 最多接受 8 个计划步骤，校验步骤顺序、未知工具名，并要求数据型任务至少包含一个工具调用步骤；计划不合格时会把问题反馈给模型进行一次修正。
- **工具幂等**：`ToolIdempotencyGuard` 按工具名和排序后的 JSON 入参生成哈希，成功结果写入 Redis 10 分钟；重复调用返回 `_idempotent=true` 的缓存结果。
- **三级恢复**：`AgentRecoveryPolicy` 将错误分为 `TRANSIENT`、`PARAMETER`、`SEMANTIC`。瞬时错误重试原调用，参数错误要求模型修正入参或输出格式，语义错误清空当前计划并触发 replan 事件。

### 跨 Run 任务系统与 Checkpoint

复杂目标（如"清掉所有薄弱标签题"）需要跨多次 run 才能完成。Agent Runtime 通过两套机制协同实现跨 run 持续推进：

**任务图**（`TaskGraphService` + `FileTaskBoard`）：

- LLM 可以将大目标拆分为带依赖图的 `TaskRecord` 项（`blockedBy` / `blocks`）。每条记录包含 subject、description、status（`PENDING` / `IN_PROGRESS` / `COMPLETED`）、owner 和 groupId。
- 任务记录通过 `FileTaskBoard` 持久化到 `.tasks/task_N.json`——跨 run、跨 JVM 重启不丢失。
- `task_create`、`task_update`、`task_get`、`task_list` 注册为 Agent 核心工具。任务自动以当前 `runId` 作为 `groupId`，实现 run 级隔离。
- 任务标记 `COMPLETED` 后，`unlockFollowers` 自动解除被阻塞任务的依赖关系。`ready` 字段告诉模型哪些任务已就绪。

**Checkpoint**（对话状态快照，用于跨 run 恢复上下文）：

- 每轮模型决策后，当前状态快照写入 `.tasks/checkpoint_{runId}.json`：包含 `runId`、`turnCount`、goal、plan、todos 和最近 20 条消息。
- 下次 run 启动时，`injectCheckpointContext` 自动扫描最近一次非当前 run 的 checkpoint，注入结构化 system message：上次目标、已完成轮数、未完成 todos，以及从 `FileTaskBoard` 查询的未完成 `TaskRecord` 列表。
- 清理策略：run 正常结束且 todos 和 TaskRecord 均无未完成项时删除 checkpoint；有未完成工作的 checkpoint 保留以供下次 resume。

**单一真相源**：`TaskRecord` 数据只存在于 `FileTaskBoard`。checkpoint 仅存储对话上下文（messages、todos、plan），不存任务数据。resume 时通过 `groupId`（即上次的 `runId`）从 `FileTaskBoard` 查询任务信息。避免数据冗余，恢复逻辑清晰。

**runId 对齐**：`AgentTask.taskId`（数据库记录）、`AgentLoopState.runId`（内存中）和 `TaskRecord.groupId`（文件任务板）三者一致，数据库 trace、checkpoint 文件和任务记录可互相追溯。

## 核心接口

认证：

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`

聊天：

- `GET /api/ai/chat` - SSE 流式聊天

Hot100：

- `GET /api/hot100/problems`
- `GET /api/hot100/problems/{slug}`
- `POST /api/hot100/progress`
- `GET /api/hot100/progress`
- `GET /api/hot100/weak-tags`
- `GET /api/hot100/tag-mastery`
- `GET /api/hot100/wrong-book`
- `POST /api/hot100/wrong-book/analyze`
- `GET /api/hot100/recommendations`
- `GET /api/hot100/study-plan`

Agent：

- `POST /api/agent/hot100/run`
- `POST /api/agent/hot100/run/stream` - Agent SSE 实时运行
- `POST /api/agent/hot100/tasks`
- `GET /api/agent/hot100/tasks/{taskId}`
- `GET /api/agent/hot100/tasks/{taskId}/trace`
- `GET /api/agent/hot100/tasks/{taskId}/steps`
- `GET /api/agent/hot100/tasks/{taskId}/runtimes/{runtimeId}/steps`
- `GET /api/agent/memory`
- `GET /api/agent/memory/profile`
- `POST /api/agent/memory`

## 技术栈

- 后端：Java 21、Spring Boot 3.5、Spring Security、Spring Data JPA、Bean Validation、Actuator
- AI：LangChain4j、DashScope/Qwen、SSE 流式输出、可选 MCP 集成
- 数据：MySQL 8、Flyway、Redis 缓存和本地 fallback
- 前端：Vue 3、Vite、Axios
- 工程化：Docker、Docker Compose、Maven Wrapper
- 测试：JUnit 5、Spring Boot Test、Agent 核心测试和 API smoke test

## 快速启动

创建环境文件：

```powershell
Copy-Item .env.example .env
Copy-Item ai-code-helper-frontend/.env.example ai-code-helper-frontend/.env
```

至少配置：

- `DASHSCOPE_API_KEY`
- `APP_AUTH_JWT_SECRET`
- MySQL 连接配置
- 如果使用完整 compose 栈，需要配置 Redis
- 如果开启 WebSearch MCP，需要配置 `APP_MCP_ENABLED`、`APP_MCP_SSE_URL`、`APP_MCP_WEB_SEARCH_TOOL_NAME`、`APP_MCP_WEB_SEARCH_QUERY_ARGUMENT`

使用 Docker 启动：

```bash
docker compose up -d --build
```

本地启动后端：

```powershell
.\mvnw.cmd spring-boot:run
```

本地启动前端：

```powershell
cd ai-code-helper-frontend
npm install
npm run dev
```

## 验证

运行全部后端测试：

```powershell
.\mvnw.cmd test
```

运行 Agent 核心测试：

```powershell
.\mvnw.cmd test "-Dtest=AgentMemoryServiceTest,AgentKnowledgeServiceTest,RuntimeTaskServiceTest,Hot100AgentServiceTest,AgentLoopServiceTest,AgentPromptBuilderTest"
```

构建前端：

```powershell
cd ai-code-helper-frontend
npm run build
```

## 简历描述

基于 Spring Boot + Vue 实现全栈 AI 学习助手，自研 ReAct Agent Runtime 作为核心编排引擎。后端包含 JWT 认证、Hot100 学习闭环（进度追踪、错题分析、薄弱标签、推荐题单、学习计划）、流式 AI 对话，以及自研 Agent 执行引擎。

Agent Runtime 是核心差异点：多轮 ReAct 循环、四级工具权限门控、计划书写与校验、Redis 工具幂等保护、三层渐进式上下文压缩（参考 Claude Code 策略）、工具三级恢复、事件钩子可观测、长期记忆、子代理隔离和 SSE 步骤级事件流式推送。LangChain4j 仅作为模型传输层——所有编排逻辑全部自研。工具执行通过持久化 step trace 和合并后的 runtime timeline 实现全链路可观测。

## 项目结构

```text
.
|-- ai-code-helper-frontend/
|-- src/main/java/com/yupi/aicodehelper/
|   |-- agent/
|   |   |-- core/
|   |   |-- Hot100AgentService.java
|   |   `-- Hot100AgentToolRegistry.java
|   |-- ai/
|   |-- auth/
|   |-- chat/
|   |-- controller/
|   |-- hot100/
|   |-- entity/
|   `-- repository/
|-- src/main/resources/
|   |-- db/migration/
|   |-- hot100/json/
|   |-- hot100/markdown/
|   |-- skills/
|   `-- system-prompt-role.txt
|-- docs/
|-- docker-compose.yml
`-- README.zh-CN.md
```

## 安全说明

不要提交真实 API Key、数据库密码、JWT 密钥或生产环境凭据。本地 `.env` 文件和运行时数据目录应保持在版本控制之外。
