;; Isolated candidate — not installed in the running Theseus home.
;; The evaluator gives every deny precedence over every allow.
;; These text checks are guardrails, not a shell security boundary.
;;   REGENERATION RULE: a rebuilt grant set REPLACES layer 2 only;
;;   Floor predicates are retained, with narrowly tested false-positive fixes.
;;   2026-09-06 owner-hand additions (additive, stricter floor):
;;   deny-secrets, deny-sudo, deny-constitution-self-edit. Secret
;;   exfiltration, privilege escalation, and the law rewriting itself are
;;   irreversible in effect, so they live on the durable layer.
;; Layer 2: owner grants v5 (2026-09-06, directive "loosen tool constitution"):
;;   all file writes (self-modification granted) + all shell on demand.
;;   The layer-1 floor above still has precedence. Replaces grants v4 whole.
;;   v5.1 additive (2026-09-06, directive "loop until goal similar to normal
;;   prompts"): launch_goal — the goal runner is reachable from ordinary
;;   prompts; the runner's own supervision (lock, rollback, halt pages) is
;;   the safety boundary, and only one goal may run at a time.
;;   2026-09-26 owner directive (applied via OpenCrabs, mutiny topic):
;;   deny-everything-else REMOVED — unlisted tools no longer die at this
;;   file; they fall through to the approval classifier (layer 1).
;;   The layer-1 denies above retain precedence over grants.
{:rules
 ;; -- layer 1: durable constitution --
 [{:name "deny-python"
   :pred (fn [tool args]
           (and (= tool "shell")
               (let [c (str (:cmd args))]
                 (and (not (re-matches #"\s*echo\s+(?:[A-Za-z0-9_./:=+-]+[ \t]*)+" c))
                     (re-find #"(^|[\s;&|/(`])(python3|python|pip3|pip)(\s|$)" c)))))
   :decision :deny}
  {:name "deny-rm"
   :pred (fn [tool args]
           (and (= tool "shell")
                (re-find #"(^\s*|[;&|]\s*)rm\s" (str (:cmd args)))))
   :decision :deny}
  {:name "deny-irreversible-git"
   :pred (fn [tool args]
           (and (= tool "shell")
                (let [c (str (:cmd args))]
                  (or (re-find #"git revert" c)
                      (re-find #"git checkout [^\s]+ -- \." c)
                      (re-find #"git reset --hard" c)
                     (re-find #"(?:^|[\s/])git\b[^\n|;&]*\bpush\b[^\n|;&]*(?:--force(?:-with-lease|-if-includes)?(?:=[^\s;|&]+)?(?=\s|$)|\s-[A-Za-z]*f[A-Za-z]*(?=\s|$)|\s\+[^\s;|&]+)" c)))))
   :decision :deny}
  {:name "deny-secrets"
   ;; .key/.pem match only in path context (/, ~, ./) — a bare `rec.key`
   ;; struct field or config map key is NOT a private key file. Bare
   ;; `cat server.key` (no path separator) is a documented residual risk,
   ;; accepted 2026-09-08 after the fence ate 3 rustdb turns on `rec.key`.
   ;; `secret` likewise requires path context (a slash, or a file
   ;; extension): 2026-09-11 the bare substring ate a commit whose
   ;; MESSAGE discussed the secrets fence — writing about secrets is not
   ;; exfiltrating one. Bare `cat production-secrets` (no slash, no
   ;; extension) is the accepted residual risk of the same shape.
   :pred (fn [tool args]
          (let [s (str (if (= tool "shell") (:cmd args) (:path args)))]
            (re-find #"(^|[/\s\"'])config\.edn(?![\w.-])|(^|[/\s\"'])\.env(?!\.example(?:$|[\s\"';|&]))|\.token|(?:^|[\s\"'])(?:~|\.{1,2})?/[\w./~@-]*secret[\w./~@-]*|(?:^|[\s\"'])[\w-]*secret[\w-]*\.(?:txt|json|edn|env|pem|key|ya?ml|conf|cfg)(?![\w])|id_rsa|id_ed25519|(?:^|[\s\"'])(?:~|\.{1,2})?/[\w./~@-]*\.(?:pem|key)(?![\w])" s)))
   :decision :deny}
  {:name "deny-sudo"
   :pred (fn [tool args]
           (and (= tool "shell")
                (re-find #"(^|[\s;&|/])sudo(\s|$)" (str (:cmd args)))))
   :decision :deny}
  {:name "deny-dd"
   :pred (fn [tool args]
           (and (= tool "shell")
                (re-find #"(^|[\s;&|/])dd(\s|$)" (str (:cmd args)))))
   :decision :deny}
  {:name "deny-chown"
   :pred (fn [tool args]
           (and (= tool "shell")
                (re-find #"(^|[\s;&|/])chown(\s|$)" (str (:cmd args)))))
   :decision :deny}
  {:name "deny-constitution-self-edit"
   :pred (fn [tool args]
           (and (contains? #{"write_file" "edit_file"} tool)
               (re-find #"(^|/)brain/rules\.clj(?:$|/)" (str (:path args)))))
   :decision :deny}
  ;; -- layer 2: owner grants v5 --
  {:name "allow-readonly-tools"
   :pred (fn [tool _] (contains? #{"read_file" "search" "git_status"} tool))
   :decision :allow}
  {:name "allow-file-writes"
   :pred (fn [tool _] (contains? #{"write_file" "edit_file"} tool))
   :decision :allow}
  {:name "allow-shell"
   :pred (fn [tool _] (= tool "shell"))
   :decision :allow}
  {:name "allow-goal-launch"
   :pred (fn [tool _] (= tool "launch_goal"))
   :decision :allow}
]}
