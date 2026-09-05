---
name: tg-topic-split
description: Split a Telegram forum group's topics into separate broadcast channels via userbot — dry-run plan, floodwait-paced forwarding with count-based resume, invite links, admin promotion, watchdog cron. Use when asked to separate/split/copy forum topics (or a group's history) into channels. Covers the whole lifecycle: enumerate topics, forward oldest→newest preserving senders, survive kills and rate limits, finalize with links + admin. Tool lives in ~/.opencrabs/projects/tg-userbot-spike (Rust, grammers). Triggers on "separate topics into channels", "split this group's topics", "copy each topic to its own channel".
---

# /tg-topic-split — Forum topics → separate channels

Copies every topic of a Telegram forum group into its own broadcast channel.
Built and battle-tested 2026-08-24 on group 2211929179 (11 topics, 15,882 msgs;
9 split clean, "General" excluded by owner directive).

## The tool

| What | Where |
|---|---|
| Crate | `~/.opencrabs/projects/tg-userbot-spike/` (Rust, grammers + grammers-client) |
| Binary | `target/release/topic_split` — build: `cargo build --release` |
| Session | `userbot.session` in crate root (**cwd-relative — always run from crate root**) |
| State | `split-state.txt` (topic ids completed, one per line, plain file) |
| Config | reads `channels.telegram.userbot` from OpenCrabs `config.toml` for creds |

Bot API **cannot** enumerate forum topics — this needs the userbot (grammers).
Session comes from `/userbot-login` if not already present.

## Subcommands

```
topic_split plan                          # dry run: list topics + msg counts, no writes
topic_split split [EXCLUDE,COMMA]         # do it; EXCLUDE = topic titles to skip
topic_split channels                      # inventory: id, title, msg count of PREFIX channels
topic_split invites                       # export fresh invite link per channel
topic_split cleanup <TITLE-SUBSTR>        # delete channels matching (IRREVERSIBLE — approval first)
topic_split rename <FROM> <NEW_PREFIX> [USED]  # sideline garbage: TRASH #N (USED = start offset)
topic_split admin @user                   # promote to full admin on every PREFIX channel
```

Behavior baked in:

- Channels are private broadcasts named `<Prefix> – <Topic>`; `PREFIX` const in
  `src/bin/topic_split/main.rs` (currently `Worklog – `). Change per job.
- Forwards preserve original senders, oldest→newest, deterministic order.
- **Count-based resume**: a half-filled dest channel is REUSEd; "already done" =
  dest msg count − 1 service msg. Never re-forwards completed prefixes.
- **Pacing**: 3s between 40-msg chunks; ×2 per flood-wait (cap 30s); server
  floodwait slept +300s buffer; transient 500s retried after 60s.
  Handles `FLOOD_WAIT` and `FLOOD_PREMIUM_WAIT`.

## Standard run sequence

1. `topic_split plan` → post table (topic / msgs) to the user, get explicit go.
2. Confirm exclusions (huge topics are floodwait bombs — split them only on
   explicit request; ~15k msgs ≈ hours at safe pace).
3. Launch **detached**, from crate root, log to file:
   `nohup .../topic_split split 'General' > split-final.log 2>&1 &`
4. Arm watchdog cron (~10 min) — pgrep guard, relaunch if dead, finalize on
   `ALL_DONE` in the log: leftover-channel `rename` → `TRASH #N`, `admin @user`,
   `invites`, post links to the originating chat, remove the job.
5. Post invite links. Private channels are **invisible in search** — links are
   the only inspection path. Generate them early if the user asks to inspect.

## Hard rules (paid for with duplicate channels — do not relearn)

1. **Never trust a bash-tool timeout as a kill.** "Timed-out" launches survive
   and race; two splitters = duplicate channels. Before ANY launch: `pgrep -f
   topic_split` and kill strays. One splitter in flight, ever.
2. **Never launch long runs inline.** Detach + log file + watchdog cron with
   pgrep guard. Session-held processes die with the session/tool call.
3. **Count-based resume only.** Forward-header parsing (`channel_post`) breaks
   on nested forwards from PMs → silent mass duplication. Deterministic order
   makes message-count a complete progress signal.
4. **Floodwait is the default state, not an error.** Slow pacing from the
   start; multiplicative backoff; +300s over whatever the server demands.
5. **Sideline, don't delete.** Partial/garbage channels → `rename` to
   `TRASH <title> #N` (pass USED offset so numbers don't collide). Actual
   `cleanup` deletion = irreversible = explicit user approval only.
6. **Sequential file edits only.** Parallel `edit_file` calls on one file race;
   last write wins, earlier edits silently lost. One edit, verify, next.
7. **Source group is read-only.** Forwarding only; nothing in the origin group
   is ever edited, deleted, or reorganized.
8. **Creator transfer needs the user's 2FA password** (API rule). Practical
   ceiling: full admin incl. `add_admins`. Offer transfer only with the user
   present.

## Finalize checklist

- [ ] `ALL_DONE` in split log; `channels` shows expected counts
- [ ] leftover partials renamed `TRASH #N` (or wiped on explicit approval)
- [ ] `admin @user` promoted
- [ ] `invites` links posted to the originating chat
- [ ] watchdog cron removed; no `topic_split` process alive
- [ ] source group untouched (it always is — say so in the report)
