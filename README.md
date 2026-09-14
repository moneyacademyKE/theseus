# Theseus

Theseus is a powerful AI agent harness built on Babashka.

It provides a local, hackable agentic shell with provider calls, configurable tool approvals (constitution floor always enforced), goal-driven autonomous work with durable workspaces, session metadata, usage tracking, scheduler/daemon workflows, a Telegram polling adapter with a durable outbox, and a small rich rendering layer.

## Requirements

- Babashka (`bb`)
- Optional: `sqlite3` for SQLite-backed memory tests/features

## Quick Start

Zero-config first turn (fake provider answers `pong`):

```sh
bb agent "say pong"
```

Check your setup — doctor is step 2, not a footnote. On a fresh home it tells you exactly what's missing (telegram config, home dir) and what's fine:

```sh
bb doctor
```

Then the local shell:

```sh
bb memory add "Project codename is Theseus"
bb memory search "codename"
bb session current
bb stats
```

## Telegram setup

The product surface. Doctor's `:telegram-config` check requires a `:telegram` map with a non-blank `:token` — everything else is optional.

1. **Get a token** — open [@BotFather](https://t.me/BotFather), send `/newbot`, follow the prompts, copy the token.
2. **Copy the template** — `config.example.edn` is annotated with every key the system reads:

   ```sh
   cp config.example.edn "$OPENCRABS_HOME/config.edn"   # or ~/.opencrabs-bb/config.edn
   ```

3. **Fill it** — replace `BOTFATHER-TOKEN-HERE` with the token. To restrict who talks to the bot, set `:allowed-chat-ids` / `:allowed-user-ids` (find your ids via [@userinfobot](https://t.me/userinfobot)). To use a real model, point `:provider` at a `:providers` entry and fill its `:api-key`.
4. **Verify** — `bb doctor` should show no errors (`:telegram-config` OK, `:provider-config` OK if you set a real provider).
5. **Run** — `bb telegram poll` (one instance at a time; `state/poller.pid` is the lock). Boot prints `theseus vX.Y.Z booting` plus a per-check health summary; any error is also DMed to `:notify {:chat-id …}` if set.

To use goals in a forum group: add the bot to the group, then `/goal <spec>` in a topic. The group entry in `:telegram {:groups {…}}` controls which ids are honored there.

## Commands

- `bb agent [--ask] <prompt>` runs one agent turn.
- `bb goal <config.edn> [--max-iters N] [--once]` runs a goal workspace directly; `bb goal status|pause|resume|stop <config.edn>` controls it. (The usual entry point is chat: `/goal <spec>` in a topic — see below.)
- `bb skill list` lists validated `skills/<name>/SKILL.md` workflows.
- `bb skill run <name> [input...]` runs a skill through the existing agent loop.
- `bb skill-economy` renders a claims-vs-reads ledger over goal workspaces and names prune candidates.
- `bb memory add <text>` stores memory.
- `bb memory search <query>` searches memory.
- `bb model set <session-id> <provider> <model>` stores session model selection.
- `bb model current <session-id>` prints effective model selection.
- `bb session list` lists session metadata.
- `bb session current` prints current session metadata.
- `bb session set-cwd <session-id> <cwd>` updates session working directory.
- `bb schedule add/list/remove/run ...` manages scheduled prompts; scheduled turns run under a constant approver (the cron lane has no human).
- `bb daemon start [--once] [--max-runs n] [--interval-ms n]` runs schedules.
- `bb telegram poll` runs the long-polling service (one instance at a time — `state/poller.pid` lock). `bb telegram poll-once` polls once. Replies use bounded 429 retry and HTML-to-plain fallback; authorized inbound files persist inertly under `channel_attachments/telegram/`. Every outbound message persists in the outbox before the send attempt.
- `bb stats` prints the ops summary: usage events/tokens, outbox depth + dead letters, goal verdict counts.
- `bb doctor` runs health checks (config, provider reachability, writability).
- `bb release vX.Y.Z [--dry-run]` cuts a release: promotes `## Unreleased` in the changelog, bumps `version.clj`, commits and tags. Pushing stays a separate explicit act.
- `bb usage report` summarizes persisted usage events.
- `bb config doctor` validates configuration.
- `bb ui status` prints local status.

## Telegram goals from chat

In a forum group, `/goal <spec>` in a topic launches a goal owned by that topic: an ack lands immediately, a progress message edits in place while the author works (consecutive tool runs compress into `tool ×N` lines under Telegram's length limit), and the verdict + declared deliverables arrive in the topic when the run settles. A second `/goal` in a busy topic queues (durable FIFO, `:goal/max-concurrent` cap) instead of being refused. `/goal status [name]` and `/goal cancel` work from chat.

## State

Theseus stores state under `OPENCRABS_HOME` when set, otherwise `~/.opencrabs-bb`.

Durable state lives in `state/` (a hygiene gate pins an allowlist; scratch is exiled to `scratch/`):

- `config.edn`
- `state/sessions/*.edn` (tool-result fields capped at 8KB at persistence)
- `state/session-metadata/*.edn`, `state/session-models/`, `state/session-summaries.edn`
- `state/usage.edn`, `state/usage-index.db`
- `state/outbox/` (unsent intents; drained every poll cycle, dead-letters after 5 attempts)
- `state/telegram-offset.edn`, `state/telegram-seen.edn`, `state/telegram-replies/`
- `state/poller.pid` (instance lock — a live foreign pid refuses the boot)
- `state/goal-queue.edn` (durable FIFO, created when goals queue)
- `state/schedules.edn`, `state/schedule-runs.edn`
- `state/digest.edn`, `state/memory.edn`
- `goals/` (registry `active.edn` + one workspace per goal: spec, config, ledgers, run.log, verdicts)
- `skills/<name>/SKILL.md` (YAML frontmatter plus inert workflow prompt body)
- `channel_attachments/telegram/<chat-id>/[topic-<thread-id>/]` (authorized inbound files, inert bytes)

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

Approvals and goals:

```edn
{:approval/mode :auto-all        ; substitute for the default :ask — no human checkpoint.
                                 ; The constitution (brain/rules.clj) still vetoes first.
 :max-tool-rounds 200            ; chat-turn tool budget
 :goal {:max-authoring-rounds 200
        :authoring-timeout-ms 480000   ; idle fuse on tool-call emits
        :authoring-ceiling-ms 2700000  ; hard ceiling even with progress
        :max-concurrent 2
        :author-model "..."}}          ; optional: pin authoring to a specific model
```

## Verification

```sh
bb test:bb-agent   # core unit suites
bb test:goal       # goal-runner unit tests
bb test:e2e:all    # full composite (includes boot-load pins, state hygiene, fake-server telegram flows)
```

## Documentation

See `docs/babashka-rewrite/` for the product spec, roadmap, tasklist, and ADR. Audits and release records live in `docs/audits/` and `docs/roadmaps/`; `docs/INDEX.md` is the lineage record.

## Releases & rollback

The running version is printed at poller boot (`theseus vX.Y.Z booting`,
first line of `state/telegram-poll.log` after a restart). Tags mark every
release; `git tag` lists them.

Roll back to a known-good release (local service, no push required):

```sh
git checkout v1.0.0
launchctl kickstart -k gui/$(id -u)/com.theseus.telegram
tail -f ~/theseus/state/telegram-poll.log   # watch the boot health lines
```

Durable state (`state/`, `goals/`) is forward-compatible: sessions, the
outbox, the poll offset, and goal workspaces survive any checkout, so a
rollback costs nothing that already happened.
