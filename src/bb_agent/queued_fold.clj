(ns bb-agent.queued-fold
  "Queued-message folding context note (ported from OpenCrabs queued_fold.rs).
   When a follow-up arrives mid-turn and folds into the same run after an
   earlier round drafted text that was never delivered, tells the model so it
   covers both requests cleanly instead of replying 'as above' to an unseen draft.")

(def not-delivered-note
  (str "[Theseus] The reply you drafted just now was never delivered to the user: "
       "a newer message arrived while you were writing it, and this turn continues "
       "with that message. Your draft is in the conversation above, but the user has "
       "not seen any of it. Answer both requests in the final reply, restating "
       "anything from the draft the user still needs. Do not refer to a previous "
       "answer, and do not write a delta like \"as above\"."))

(defn injected-context
  "Build the user text injected for a queued message folding into a running turn.
   Prefixes `not-delivered-note` ONLY when `had-draft?` is true."
  [had-draft? queued]
  (if had-draft?
    (str not-delivered-note "\n\n" queued)
    (str queued)))
