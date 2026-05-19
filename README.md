# AI Code Helper — Hot100 Algorithm Practice Agent

[中文文档](./README.zh-CN.md)

A Spring Boot + Vue full-stack project that combines traditional backend engineering with a self-built ReAct Agent Runtime for algorithm coaching. The project is built for backend internship interviews — it demonstrates system design, framework integration, and the ability to build LLM orchestration logic beyond calling an API.

## Why This Project

Most "AI projects" in resumes are thin wrappers around an API call. This one is different: **the Agent Runtime is the product**. It implements a full ReAct loop with tool permission gating, execution planning and validation, three-tier context compaction, tiered tool recovery, idempotent tool execution, sub-agent isolation, and execution trace streaming — all written from scratch on top of LangChain4j as the model transport layer.

## Technical Highlights

### Agent Runtime (self-built ReAct engine)

- **Custom ReAct loop**: `model turn → tool_use → tool_result → next turn → final_answer`, up to 8 autonomous turns. LangChain4j is used only as the LLM client — all orchestration logic is purpose-built.
- **Plan-before-act stage**: before long autonomous runs, the Agent asks the model for a structured JSON execution plan, validates step order, tool existence, and tool-using coverage, and asks for a corrected plan when validation fails. The validated plan is rendered back into later prompts so the ReAct loop has an explicit execution contract.
- **Three-tier context compaction** inspired by Claude Code: Tier 1 scores and removes low-value old messages (Snip), Tier 2 trims large JSON tool outputs to essential fields (Microcompact), Tier 3 calls the model to produce a structured semantic summary that replaces the entire conversation (Autocompact). Each tier gates the next — lightweight operations stay in-process, expensive ones run only when needed.
- **Tool permission gate** with four levels: `READ`, `WRITE`, `EXTERNAL`, `SENSITIVE`. Denied calls return structured `tool_result` errors instead of throwing exceptions, so the model can adapt its strategy rather than crashing the loop.
- **Tool idempotency guard**: Redis-backed SHA-256 hashes of tool name + canonical input detect duplicate calls within a 10-minute TTL and replay cached results, reducing repeated writes and duplicate external operations during retries.
- **Three-tier tool recovery**: transient failures are retried automatically, parameter/model-output failures are returned as structured correction prompts, and semantic failures trigger a replan path. Unknown tools, malformed JSON, tool exceptions, and max-turn exits all emit typed recovery decisions.
- **Hook system with events** for model turns, tool calls, permission denial, compaction, plan validation, replan, and recovery. Observers receive these events to persist steps, update heartbeats, and push SSE streams — all without coupling business logic to the loop.

### AI Integration

- **MCP dual-channel routing**: the same MCP external tools (e.g. DashScope WebSearch) are routed into both the streaming chat path (via LangChain4j's native `McpToolProvider`) and the Agent tool registry (dynamically registered as `mcp_*` tools with `EXTERNAL` permission). `ObjectProvider<McpClient>` makes MCP optional — when not configured, external tools are silently skipped.
- **Hybrid explainable RAG**: local retrieval loads Hot100 markdown and JSON resources, splits them into section-level chunks enriched with slug, difficulty, tags, and pattern metadata, then merges Redis-backed vector similarity with deterministic keyword scoring through `HybridRanker`. Results include scores, matched terms, and content windows, so retrieval is debuggable rather than a black box.
- **Agent step-level SSE streaming**: unlike chat token streaming (which LangChain4j handles natively), this streams Agent execution events (`model_turn`, `tool_result`, `tool_error`, `finish`) in real time. Uses Reactor `Sinks.Many` to bridge the blocking Agent loop to a reactive SSE flux.

### Long-Term Memory System

- Five memory types: `USER_PREFERENCE`, `WEAKNESS`, `WRONG_ANSWER`, `NEXT_ACTION`, `NOTE`.
- Recall uses keyword tokenization + hit-count scoring + importance weighting — not a simple `LIKE` query. Results are sorted by relevance then importance then recency.
- Memory is saved automatically during progress updates — wrong answers, weak knowledge points, and next actions are persisted as structured memories from the `rememberProgress` hook.

### Backend Engineering

- Spring Boot 3.5, Java 21, Spring Security with JWT (access + refresh tokens).
- JPA + Flyway migrations for schema evolution, Redis caching with per-category TTLs and local fallback.
- Docker Compose for full-stack deployment, Maven Wrapper for reproducible builds.
- `@Scheduled` watchdog that inspects runtime heartbeat timestamps every 30 seconds and marks stalled slots as FAILED after 5 minutes.

## Architecture

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

The model can decide which registered tool to call, but actual execution stays inside the backend. Each tool has a name, description, permission level, and Java handler, so model reasoning remains flexible while system behavior remains controlled.

## Agent Runtime Data Model

The Agent module separates a user goal from execution attempts and step-level traces:

```text
AgentTask         — what the user asked for (goal, status, finalAnswer)
  └─ RuntimeSlot  — one execution attempt (attempt, executorId, heartbeatAt)
       └─ AgentStep — each model turn or tool call (stepOrder, toolName, latencyMs)
```

A task can have multiple runtime slots (retries), each slot produces multiple steps. This design makes retries, trace inspection, and executor failover straightforward.

## Background Agent Tools

The Hot100 Agent can launch focused background work without blocking the current ReAct loop:

- `background_run`: starts a background analysis task and immediately returns a `task_id` backed by a runtime slot.
- `background_check`: checks the runtime slot status, stage, progress, error, and output for a previously launched background task.
- Background tasks run through `RuntimeTaskService` on the configured Spring executor and reuse sub-agent execution for focused analysis.
- Completed, failed, or timed-out background tasks push `BackgroundNotification` records. The main Agent loop drains these notifications before each model turn and injects them back into the conversation as new context.
- The watchdog uses runtime heartbeats to mark stalled background slots as `FAILED` after 5 minutes.

## Hot100 Domain Features

- Browse and filter Hot100 problems by keyword, tag, and difficulty.
- View problem details, patterns, core ideas, complexity, pitfalls, and markdown notes.
- Save learning progress with notes, wrong reasons, weak knowledge points, AI feedback, and next actions.
- Maintain a wrong book and generate wrong-answer analysis.
- Compute weak tags and tag mastery from progress records.
- Generate recommendation lists and study plans.
- Run a Hot100 Agent task synchronously, submit it as a background task, or stream live execution events through SSE.
- Inspect model/tool execution traces by task and runtime slot.

## AI and RAG

`AgentKnowledgeService` provides the Agent's `retrieveKnowledge` tool.

The current retrieval pipeline:

- loads `src/main/resources/hot100/markdown/*.md`
- loads `src/main/resources/hot100/json/*.json`
- splits markdown into section-level chunks
- enriches chunks with problem metadata such as slug, title, difficulty, tags, pattern, and summary
- optionally ingests docs and Hot100 notes into LangChain4j embeddings
- persists embedding vectors in Redis through `RedisEmbeddingStore` so cold starts can reuse the index
- queries both the in-memory `ContentRetriever` path and Redis vector store when available
- reranks candidates with `HybridRanker`, using 60% vector score and 40% normalized keyword score
- returns explainable retrieval fields: source, slug, title, section, score, matched terms, and content

This gives the project a deterministic local RAG path for tests and demos, while supporting embedding-based semantic search when RAG is enabled.

## MCP Integration

The project has optional MCP integration for external web-search capabilities. MCP is wired into both the normal streaming chat path and the Hot100 Agent tool registry.

- `McpConfig`: creates the MCP client and `McpToolProvider` when MCP is enabled.
- `AiCodeHelperServiceFactory`: attaches the MCP tool provider to the LangChain4j AI service.
- `QwenMcpCapabilityService`: injects an MCP capability notice into the chat prompt.
- `AiController`: passes the MCP capability notice into the SSE chat request.
- `Hot100AgentToolRegistry`: dynamically registers MCP tools as `mcp_*` Agent tools with `EXTERNAL` permission when an MCP client is available.

MCP is disabled by default. To enable DashScope WebSearch MCP, configure:

```env
DASHSCOPE_API_KEY=your_dashscope_api_key
APP_MCP_ENABLED=true
APP_MCP_SSE_URL=https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp
APP_MCP_WEB_SEARCH_TOOL_NAME=web_search
APP_MCP_WEB_SEARCH_QUERY_ARGUMENT=query
```

`APP_MCP_API_KEY` is optional because the backend falls back to `DASHSCOPE_API_KEY`:

```yaml
app.mcp.api-key: ${APP_MCP_API_KEY:${DASHSCOPE_API_KEY:}}
```

When using Docker Compose, the root `.env` file is loaded by Compose. When running locally with `.\mvnw.cmd spring-boot:run`, PowerShell does not automatically load `.env`; export the variables in the current shell first:

```powershell
$env:APP_MCP_ENABLED="true"
$env:APP_MCP_SSE_URL="https://dashscope.aliyuncs.com/api/v1/mcps/WebSearch/mcp"
$env:APP_MCP_WEB_SEARCH_TOOL_NAME="web_search"
$env:APP_MCP_WEB_SEARCH_QUERY_ARGUMENT="query"
.\mvnw.cmd spring-boot:run
```

For Agent tasks, external MCP tools are permission-gated. A request must explicitly allow external tools, otherwise tools registered with `EXTERNAL` permission are denied by the backend permission gate.

## SSE Streaming

Two streaming paths:

| Endpoint | Purpose | Granularity |
|---|---|---|
| `GET /api/ai/chat` | AI chat | Token-level (word by word) |
| `POST /api/agent/hot100/run/stream` | Agent execution | Step-level (model_turn, tool_result, tool_error, finish, error) |

Agent events carry `type`, `turn`, `toolName`, `data`, `latencyMs`, and `status`. The SSE stream is built with Reactor `Sinks.Many` to bridge the blocking Agent loop to a non-blocking SSE flux.

## Context Compaction

Three-tier strategy — lightweight operations first, expensive model calls only when needed:

| Tier | What | Cost |
|---|---|---|
| TIER1_SNIP | Score and remove low-value old messages, keep original goal + recent turns | O(n), in-memory |
| TIER2_MICROCOMPACT | Trim large JSON tool outputs to essential fields, truncate long text | O(n), in-memory |
| TIER3_AUTOCOMPACT | Model-generated structured summary (`goal`, `done`, `findings`, `remaining`) replacing all messages | Network I/O + tokens |

Tiers 1 and 2 directly mutate the message list without calling `state.compact()`. Only Tier 3 calls `state.compact()`, which clears all messages and replaces them with the original goal + the model's summary.

## Planning, Idempotency, and Recovery

- **Planning**: `AgentPlanValidator` accepts at most 8 plan steps, verifies step order, rejects unknown tool names, and requires at least one tool-using step for data-backed work. Invalid plans are sent back to the model for one correction attempt before the Agent proceeds.
- **Idempotency**: `ToolIdempotencyGuard` hashes tool calls by name and sorted JSON input, stores successful outputs in Redis for 10 minutes, and returns `_idempotent=true` cached results for duplicate calls.
- **Tiered recovery**: `AgentRecoveryPolicy` classifies errors into `TRANSIENT`, `PARAMETER`, and `SEMANTIC`. Transient errors retry the same call, parameter errors ask the model to fix input/output shape, and semantic errors clear the current plan and emit a replan event.

## Main APIs

Auth:

- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `GET /api/auth/me`

Chat:

- `GET /api/ai/chat` - SSE streaming chat

Hot100:

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

Agent:

- `POST /api/agent/hot100/run`
- `POST /api/agent/hot100/run/stream` - SSE live Agent run
- `POST /api/agent/hot100/tasks`
- `GET /api/agent/hot100/tasks/{taskId}`
- `GET /api/agent/hot100/tasks/{taskId}/trace`
- `GET /api/agent/hot100/tasks/{taskId}/steps`
- `GET /api/agent/hot100/tasks/{taskId}/runtimes/{runtimeId}/steps`
- `GET /api/agent/memory`
- `GET /api/agent/memory/profile`
- `POST /api/agent/memory`

## Tech Stack

- Backend: Java 21, Spring Boot 3.5, Spring Security, Spring Data JPA, Bean Validation, Actuator
- AI: LangChain4j, DashScope/Qwen, SSE streaming, optional MCP integration
- Data: MySQL 8, Flyway, Redis cache with local fallback
- Frontend: Vue 3, Vite, Axios
- DevOps: Docker, Docker Compose, Maven Wrapper
- Testing: JUnit 5, Spring Boot Test, focused Agent and API smoke tests

## Quick Start

Create environment files:

```powershell
Copy-Item .env.example .env
Copy-Item ai-code-helper-frontend/.env.example ai-code-helper-frontend/.env
```

Configure at least:

- `DASHSCOPE_API_KEY`
- `APP_AUTH_JWT_SECRET`
- MySQL connection settings
- Redis settings if you use the full compose stack
- Optional MCP settings if WebSearch MCP is enabled: `APP_MCP_ENABLED`, `APP_MCP_SSE_URL`, `APP_MCP_WEB_SEARCH_TOOL_NAME`, `APP_MCP_WEB_SEARCH_QUERY_ARGUMENT`

Start infrastructure and application with Docker:

```bash
docker compose up -d --build
```

Run backend locally:

```powershell
.\mvnw.cmd spring-boot:run
```

Run frontend locally:

```powershell
cd ai-code-helper-frontend
npm install
npm run dev
```

## Verification

Run all backend tests:

```powershell
.\mvnw.cmd test
```

Run focused Agent tests:

```powershell
.\mvnw.cmd test "-Dtest=AgentMemoryServiceTest,AgentKnowledgeServiceTest,RuntimeTaskServiceTest,Hot100AgentServiceTest,AgentLoopServiceTest,AgentPromptBuilderTest"
```

Build frontend:

```powershell
cd ai-code-helper-frontend
npm run build
```

## Resume Summary

Built a full-stack AI learning assistant (Spring Boot + Vue) with a self-built ReAct Agent Runtime for algorithm coaching. The backend implements JWT authentication, Hot100 learning workflows (progress tracking, wrong-answer analysis, weak-tag analytics, recommendations, study plans), streaming AI chat, and a custom Agent execution engine.

The Agent Runtime is the core differentiator: a multi-turn ReAct loop with four-level tool permission gating, plan generation and validation, Redis-backed tool idempotency, three-tier context compaction (inspired by Claude Code), three-tier tool recovery, hook-based observability, long-term memory, sub-agent isolation, and SSE event streaming. LangChain4j is used as the model transport layer — all orchestration logic is purpose-built. Tool execution is fully observable through persistent step traces and a merged runtime timeline.

## Project Structure

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
`-- README.md
```

## Security Notes

Do not commit real API keys, database passwords, JWT secrets, or production credentials. Local `.env` files and runtime data directories should stay outside version control.
