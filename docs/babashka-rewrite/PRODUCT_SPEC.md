# Product Spec: Babashka-Only Agent

## Problem

OpenCrabs delivers broad autonomous-agent utility, but the Rust implementation carries high native, channel, media, TUI, and dependency complexity. The rewrite should preserve the high-value user outcomes while removing avoidable implementation surface.

## Product Principle

Build an agentic shell, not a feature-parity clone.

The product is a local, hackable Babashka application that can chat with LLM providers, run approved tools, manage files, remember useful facts, and run scheduled work. Every feature must justify its complexity with direct user utility.

## In Scope

Only high and medium viability components are included.

| Component | Viability | Product Commitment |
|---|---|---|
| CLI agent | High | Provide prompt, chat, session, config, and tool commands. |
| Tool execution | High | Execute registered tools through explicit approvals and JSON results. |
| Config and filesystem workflows | High | Store user config, sessions, tools, and memory in predictable local files. |
| Provider HTTP calls | High | Support OpenAI-compatible and Anthropic-compatible request/response shapes with native tool-call parsing. |
| JSON tool protocol | High | Use stable JSON envelopes for model tool requests and local tool results. |
| File and git workflows | High | Provide safe read, write, search, and git helper tools. |
| Simple memory | High | Start with EDN files; support add, list, search, and attach-to-turn. |
| Babashka task runner | High | Use `bb` tasks for tests, e2e flows, linting, and local operations. |
| SQLite-backed memory | Medium | Supported through `sqlite3` while keeping the memory API stable. |
| Daemon and scheduler | Medium | Add a supervised long-running loop for scheduled jobs after CLI stability. |
| Telegram bot | Medium | Add after daemon exists; use polling first for low operational complexity. |
| Slack bot | Medium | Add after Telegram if channel abstraction remains simple. |
| Interactive approvals | High | Support CLI prompts and channel replies for pending tool requests. |
| Session metadata | High | Store cwd, provider/model, and timestamps independently from turn history. |
| Usage tracking | Medium | Persist provider/model/token/cost estimates for reports. |
| Rich rendering | Medium | Render a small internal AST to terminal, Telegram, and Slack. |
| Browser automation via external CLI | Medium | Shell out to existing browser tools instead of embedding protocol clients. |
| Document parsing | Medium | Provide a safe Babashka-native text reader first; add external parsers only behind explicit configuration and tests. |
| Simple terminal UI | Medium | Provide lightweight menus/status if needed; do not rebuild Ratatui parity. |

## Out Of Scope

| Component | Reason |
|---|---|
| Direct Rust-to-Babashka transpilation | Incidental complexity with low correctness confidence. |
| Full OpenCrabs feature parity | Too much surface before core value is proven. |
| WhatsApp native client | Low viability and high protocol complexity. |
| Embedded local STT/TTS | Native model/audio dependency burden is too high. |
| Rich TUI parity | Not essential to the agentic shell outcome. |
| Autonomous self-modification | High risk; only advisory improvement proposals are allowed. |
| Single native binary parity | Babashka runtime is acceptable for this product. |

## User Outcomes

| Outcome | Acceptance Test |
|---|---|
| Ask a model from the terminal | `bb agent "say pong"` prints a final answer and persists a session turn. |
| Run a safe local tool | A model-requested tool call runs only after approval or explicit auto-approval. |
| Work on files | The agent can read, search, and write workspace files under policy. |
| Remember useful facts | `bb memory add` and `bb memory search` work without a database. |
| Repeat scheduled work | A scheduler can run a named prompt/tool workflow and log the result. |
| Add a chat channel | Telegram polling can deliver a prompt to the same agent turn loop. |
| Approve tools interactively | `bb agent --ask` prompts for pending tools and Telegram replies can resolve pending channel approvals. |
| Inspect sessions | `bb session list/current/set-cwd` manages cwd and model/provider metadata. |
| Track usage | `bb usage report` summarizes persisted token and cost estimates. |

## Core Data Contracts

All boundaries use data, not hidden runtime coupling.

### Turn Input

```edn
{:session/id "default"
 :user/input "summarize this repo"
 :mode :interactive
 :approval :ask
 :context {:cwd "/workspace"}}
```

### Provider Request

```edn
{:provider :openai-compatible
 :model "gpt-4.1-mini"
 :messages [{:role :user :content "hello"}]
 :tools [{:name "read_file" :schema {...}}]}
```

### Tool Request

```edn
{:tool/name "shell"
 :tool/args {:cmd "git status --short"}
 :approval/required? true}
```

### Tool Result

```edn
{:tool/name "shell"
 :status :ok
 :stdout ""
 :stderr ""
 :exit/code 0}
```

### Pending Approval

```edn
{:approval/id "uuid"
 :session/id "telegram-4242"
 :channel :telegram
 :tool/name "shell"
 :tool/args {:cmd "git status --short"}
 :created/at "2026-07-14T00:00:00Z"}
```

### Session Metadata

```edn
{:session/id "default"
 :cwd "/workspace"
 :provider :openai-compatible
 :model "gpt-5-mini"
 :created/at "2026-07-14T00:00:00Z"
 :updated/at "2026-07-14T00:01:00Z"}
```

### Usage Event

```edn
{:usage/event :turn
 :session/id "default"
 :provider :openai-compatible
 :model "gpt-5-mini"
 :tokens/input 120
 :tokens/output 40
 :tokens/cache-read 0
 :tokens/cache-write 0
 :tokens/total 160
 :cost/estimate-usd 0.00055
 :created/at "2026-07-14T00:01:00Z"}
```

### Rich AST

```edn
{:type :document
 :children [{:type :heading :level 2 :children [{:type :text :text "Status"}]}
            {:type :paragraph :children [{:type :text :text "Ready"}]}]}
```

## Quality Bar

- Every public command has an e2e test.
- Every agent loop phase has a failing test before implementation.
- Files stay below 250 lines where practical.
- Tool execution defaults to safe approval.
- Provider-originated tool calls stay denied unless local policy or user approval allows them.
- Failures are returned as data and rendered clearly.
- No channel-specific code may bypass the core turn loop.

## Rich Hickey Decision Rule

For every feature, choose the path with the best weighted balance of power, simplicity, speed, and durability.

| Question | Prefer |
|---|---|
| Can this be data? | Data over callbacks/classes. |
| Can this be one loop? | One core turn loop over per-channel logic. |
| Can this be a script? | Babashka script over embedded native dependency. |
| Can this be deferred? | Defer until an e2e test proves need. |
| Can this fail safely? | Explicit error data over implicit exceptions. |
