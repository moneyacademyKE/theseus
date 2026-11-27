# ADR 0017: Telegram Admin Auto-Registration

- **Date:** 2026-11-27
- **Status:** Accepted
- **Context:** Manually adding every group/channel ID to `config.edn` `:telegram :groups` caused silent non-responses whenever an authorized operator added Theseus as an admin to a new group or channel.

## Decisions

1. **New module `bb-agent.telegram-auto-register`:**
   - **Strict trust boundary:** auto-registers a `group`, `supergroup`, or `channel` ONLY when an existing non-bot user in `[:telegram :allowed-user-ids]` / `:allowed-chat-ids` promotes the bot to `"administrator"` or `"creator"`. Strangers, bots, private chats, and non-admin member additions are refused.
   - **Idempotent persistence:** writes `{:allowed-user-ids <existing>, :open true, :respond-to :all}` under `[:telegram :groups chat-id]` via `config/write-config!`.
2. **Two live paths in `bb-agent.telegram-lifecycle`:**
   - Added `"my_chat_member"` to `getUpdates` `allowed_updates` and dispatch `:my_chat_member` updates to `auto-reg/handle-my-chat-member!`.
   - Added `get-chat-member` fallback (`auto-reg/maybe-register-on-message!`) so groups/channels where the bot was *already* made admin before this change auto-register on the next message from an authorized user.
   - Fixed single-message dispatch to use `(media/message ...)` so `:channel_post` updates are not dropped.
