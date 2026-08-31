# Telegram Group and Forum-Topic Parity

## Context

Theseus already supports Telegram DMs, durable polling, rich HTML replies, explicit approvals, and a fail-closed allowlist. Its current identity key is only `chat_id`. That is sufficient for DMs but wrong for groups:

- a group chat ID is not the sender's user ID;
- every forum topic in one supergroup currently collapses into one session;
- outbound replies omit `message_thread_id` and therefore land in General;
- the agent cannot tell which group member spoke;
- every allowed group message triggers a turn, inviting noise and bot-to-bot loops.

OpenCrabs keeps these concerns separate. This change ports that data model, not its channel machinery.

## Verified live target

The supplied Telegram link `https://t.me/c/3995594829/1` resolves to private supergroup `-1003995594829` (**Sly Theseus**). Telegram Bot API verification with Eileen's own token reports:

- `type=supergroup`, `is_forum=true`;
- `@eileenslybot` is an administrator;
- privacy is disabled (`can_read_all_group_messages=true`);
- the bot can manage the chat and delete messages, but cannot create/rename topics (`can_manage_topics=false`).

Existing-topic parity therefore has the permissions it needs; topic administration is outside this slice.

## Target state
| Concern | Current Theseus | Target behavior |
|---|---|---|
| Authorization | `chat_id` in `:allowed-chat-ids` | DMs retain legacy chat allowlist; groups authorize by sender through global/per-group user lists or an explicitly open group |
| Group activation | Every allowed message | `:mention` by default; direct mention or reply-to-bot activates. `:all` is an explicit per-group override |
| Bot loops | No sender-bot check | Group messages from bots are ignored |
| Session identity | `telegram-<chat-id>` | Base/General stays `telegram-<chat-id>`; genuine forum topics use `telegram-<chat-id>-topic-<thread-id>` |
| Topic replies | No thread field | Every final/approval reply carries the inbound `message_thread_id`; first final chunk also replies to the inbound message |
| Group identity | Raw message text only | Agent input names the sender and group; replies include bounded replied-to context |
| Approvals | Chat-scoped | Approval state and notifications use the same chat+topic session and return to the same topic |
| Poll durability | Offset + seen set | Unchanged; ignored and denied updates are still consumed exactly once |

## Configuration

```clojure
{:telegram
 {:token "..."
  ;; Existing DM compatibility.
  :allowed-chat-ids [1608111860]
  ;; Global operators: may use DMs and groups.
  :allowed-user-ids [1608111860]
  :respond-to :mention
  :groups
  {-1003995594829
   {:allowed-user-ids [1608111860]
    :open false
    :respond-to :mention}}}}
```

Group keys may be numeric IDs or their string representation. `:open true` grants any non-bot member access only inside that configured group; it grants no DM access. Open mode remains disabled unless at least one global operator is configured.

## Routing rules

1. DMs from configured users keep the existing behavior.
2. Group messages from bots are ignored.
3. Unauthorized group messages are silently consumed.
4. In `:mention` mode, activate only on an own-username mention or a reply to the bot. A reply that explicitly addresses a different bot is ignored unless it also mentions this bot.
5. In `:all` mode, every authorized human text message activates.
6. Only `is_topic_message=true` plus a `message_thread_id` creates a topic session. Plain reply threads and General share the base chat session.
7. Telegram command suffixes such as `/approve@eileenslybot` are normalized before approval parsing.

## Explicitly outside this parity slice

Media download/STT, reactions, inline keyboards, passive group-history capture, automatic member registration (`/cowork`), and MTProto userbot behavior remain separate features. They require storage or transport boundaries Theseus does not yet own. Complecting them with session/topic correctness would make this change harder to verify and easier to break.

## Verification contract

- Two topics in one supergroup create two session files and never cross-read.
- Base/General and a topic in one group remain distinct.
- Replies and approval notifications preserve `message_thread_id`.
- Mention/reply activation and bot-sender suppression are tested through the real polling adapter.
- Group-scoped users cannot escape into DMs or other groups.
- The full `bb test:e2e:all` gate remains green.

## Rich Hickey check

The key is a tuple represented as data: `(chat-id, topic-id)`. Authorization is another tuple: `(user-id, chat-id, is-dm)`. Keeping those values separate prevents the accidental complexity in the current one-ID-for-everything design. The rejected alternative is a Telegram-specific session manager; stable session IDs and pure routing functions already compose with the existing store.
