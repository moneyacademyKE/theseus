# Theseus

Theseus is a standalone Babashka agent app extracted from the OpenCrabs Babashka rewrite work.

It provides a local, hackable agentic shell with provider calls, explicit tool approvals, session metadata, usage tracking, scheduler/daemon workflows, a Telegram polling adapter, and a small rich rendering layer.

## Requirements

- Babashka (`bb`)
- Optional: `sqlite3` for SQLite-backed memory tests/features

## Quick Start

```sh
bb agent "say pong"
bb agent --ask "try denied shell git status --short"
bb memory add "Project codename is Theseus"
bb memory search "codename"
bb session current
bb usage report
```

## Commands

- `bb agent [--ask] <prompt>` runs one agent turn.
- `bb skill list` lists validated `skills/<name>/SKILL.md` workflows.
- `bb skill run <name> [input...]` runs a skill through the existing agent loop.
- `bb memory add <text>` stores memory.
- `bb memory search <query>` searches memory.
- `bb model set <session-id> <provider> <model>` stores session model selection.
- `bb model current <session-id>` prints effective model selection.
- `bb session list` lists session metadata.
- `bb session current` prints current session metadata.
- `bb session set-cwd <session-id> <cwd>` updates session working directory.
- `bb schedule add/list/remove/run ...` manages scheduled prompts.
- `bb daemon start [--once] [--max-runs n] [--interval-ms n]` runs schedules.
- `bb telegram poll-once` polls Telegram updates once. Replies use bounded 429 retry and HTML-to-plain fallback; authorized inbound files persist inertly under `channel_attachments/telegram/`.
- `bb usage report` summarizes persisted usage events.
- `bb config doctor` validates configuration.
- `bb ui status` prints local status.

## State

Theseus stores state under `OPENCRABS_HOME` when set, otherwise `~/.opencrabs-bb`.

Important files:

- `config.edn`
- `skills/<name>/SKILL.md` (YAML frontmatter plus inert workflow prompt body)
- `state/sessions/*.edn`
- `state/session-metadata/*.edn`
- `state/approvals.edn`
- `state/usage.edn`
- `state/telegram-offset.edn`
- `state/telegram-seen.edn`
- `channel_attachments/telegram/<chat-id>/[topic-<thread-id>/]` (authorized inbound files, inert bytes)
- `state/schedules.edn`
- `state/schedule-runs.edn`

## Configuration

Minimal fake-provider config is optional because defaults are built in:

```edn
{:provider :fake
 :model "fake-deterministic"
 :session/id "default"}
```

OpenAI-compatible example:

```edn
{:provider :openai-compatible
 :model "gpt-5-mini"
 :providers {:openai-compatible
             {:base-url "https://api.openai.com/v1"
              :api-key "..."}}}
```

Anthropic-compatible example:

```edn
{:provider :anthropic-compatible
 :model "claude-sonnet-4-6"
 :providers {:anthropic-compatible
             {:base-url "https://api.anthropic.com/v1"
              :api-key "..."}}}
```

## Verification

```sh
bb test:e2e:all
```

## Documentation

See `docs/babashka-rewrite/` for the product spec, roadmap, tasklist, and ADR.
