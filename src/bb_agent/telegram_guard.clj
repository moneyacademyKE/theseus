(ns bb-agent.telegram-guard)

(defn allowed?
  "Pure owner-allowlist check. Returns true only when `chat-id` is a
  member of the :allowed-chat-ids vector inside (:telegram cfg).
  Fail-closed: missing :telegram, missing :allowed-chat-ids, or an
  empty list all deny."
  [cfg chat-id]
  (boolean
   (some #(= % chat-id)
         (get-in cfg [:telegram :allowed-chat-ids]))))
