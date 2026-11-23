(ns bb-agent.tool.process
  (:require [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [bb-agent.tool.path :as path]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; The never-auto-run floor: these stay denied even under :auto-all.
;; Everything else runs according to the configured approval mode.
(def ^:private never-auto-shell-prefixes
  ["rm " "rm\t" "rm\n" "dd " "sudo " "chown "])

(def ^:private dangerous-cmd-re
  (re-pattern
   (str "(?:"
        "(?:^|[\\r\\n;&|`(]\\s*)"
        "|(?:\\b(?:command|env|nohup|exec|xargs|eval)\\s+(?:-[^\\s]+\\s+)*)"
        ")"
        "(?:(?:/[\\w.-]+)+/|\\\\)?(rm|dd|sudo|chown)\\b")))

(defn- strip-quoted-literals
  "Strip single-quoted strings and double-quoted literals while keeping
   shell command substitutions ($(...) and `...`) visible to the safety floor."
  [cmd]
  (-> (or cmd "")
      ;; Single-quoted strings never expand commands in POSIX sh.
      (str/replace #"'[^']*'" "''")
      ;; Double-quoted strings without command substitution ($( or `).
      (str/replace #"\"(?:[^\"\\$`]|\\.)*\"" "\"\"")))

(defn- never-auto-shell-command? [cmd]
  (let [trimmed (str/trim (or cmd ""))
        unquoted (str/trim (strip-quoted-literals trimmed))]
    (or (some #(str/starts-with? unquoted %) never-auto-shell-prefixes)
        (boolean (re-find dangerous-cmd-re unquoted)))))

(defn- checked-cwd [tool-name cwd]
  (if cwd
    (path/checked-read-path tool-name cwd)
    (path/checked-read-path tool-name (config/home))))

(defn- run-shell! [{:keys [cmd cwd timeout-ms]}]
  (if (never-auto-shell-command? cmd)
    (common/error-result "shell"
                         "Denied unsafe shell command"
                         {:cmd cmd
                          :executed? false})
    (let [cwd* (checked-cwd "shell" cwd)]
      (if (= :error (:status cwd*))
        cwd*
        (let [timeout (common/safe-timeout-ms timeout-ms)
              process (p/process ["sh" "-c" cmd]
                                 {:out :string
                                  :err :string
                                  :continue true
                                  :dir (str cwd*)})
              result (deref process timeout ::timeout)
              body {:stdout (:out result)
                    :stderr (:err result)
                    :exit/code (:exit result)
                    :cwd (str cwd*)
                    :timeout-ms timeout}]
          (if (= ::timeout result)
            (do
              (p/destroy-tree process)
              (common/error-result "shell"
                                   "Timed out shell command"
                                   (assoc body :stdout "" :stderr "" :exit/code nil)))
            (if (zero? (:exit result))
              (common/ok-result "shell" body)
              (common/error-result "shell" "Shell command failed" body))))))))

(defn- git-status! [{:keys [cwd]}]
  (let [cwd* (checked-cwd "git_status" cwd)]
    (if (= :error (:status cwd*))
      cwd*
      (let [result (p/shell {:out :string
                             :err :string
                             :continue true
                             :dir (str cwd*)
                             :timeout common/default-shell-timeout-ms}
                            "git status --short --branch")]
        (common/ok-result "git_status"
                          {:stdout (:out result)
                           :stderr (:err result)
                           :exit/code (:exit result)
                           :cwd (str cwd*)})))))

(defn- parse-json-body [body]
  (try
    (json/parse-string body keyword)
    (catch Exception _
      nil)))

(defn- browser-command [url]
  (when-let [command (System/getenv "THESEUS_BROWSER_CLI")]
    [command url]))

(defn- browser-cli! [{:keys [url timeout-ms]}]
  (if (str/blank? (or url ""))
    (common/error-result "browser_cli" "browser_cli requires :url" {:executed? false})
    (if-let [command (browser-command url)]
      (let [result @(p/process command {:out :string
                                        :err :string
                                        :continue true
                                        :timeout (common/safe-timeout-ms timeout-ms)})
            parsed (parse-json-body (:out result))]
        (if (zero? (:exit result))
          (common/ok-result "browser_cli"
                            {:url url
                             :stdout (:out result)
                             :stderr (:err result)
                             :exit/code (:exit result)
                             :page (or parsed {:content (:out result)})})
          (common/error-result "browser_cli"
                               "browser_cli command failed"
                               {:url url
                                :stdout (:out result)
                                :stderr (:err result)
                                :exit/code (:exit result)})))
      (common/error-result "browser_cli"
                           "THESEUS_BROWSER_CLI is not configured"
                           {:url url :executed? false}))))

(defn- document-read! [{:keys [path]}]
  (let [target (path/checked-read-path "document_read" path)]
    (cond
      (= :error (:status target)) target

      (path/protected-secret-path? target)
      (common/error-result "document_read"
                           (str "Refused: protected secret path: " path)
                           {:path path :executed? false})

      (path/file-too-large? target common/max-read-bytes)
      (common/error-result "document_read"
                           (str "File too large: " path)
                           {:path path :max/bytes common/max-read-bytes})

      :else
      (let [text (slurp (str target))
            total (count text)
            budget common/max-doc-chars
            ;; Output budgeting: the cut is DATA, not a fact the tool keeps to
            ;; itself. Before this change document_read returned exactly
            ;; max-doc-chars of a longer file with no marker of any kind, so a
            ;; 20000-char slice of a 635358-char file was indistinguishable from
            ;; the whole file — a false-clean payload, measured 2026-11-11 on
            ;; brain/improvements.md: keys back were :path and :text, and
            ;; nothing said the read had been cut.
            ;; Shape from ruslanudintsev-ai/agent-self-learning-core,
            ;; core/commands/base.py:261 _apply_output_budgets, which appends
            ;; "... [TRUNCATED {excess} chars by Output Budget. Use specific
            ;; query/slice] ..." and sets truncated plus _budget_notice with the
            ;; real total beside what was shown.
            ;; Two decisions are ours: the marker stays INSIDE the budget, since
            ;; a notice that pushed the payload past its own ceiling would defeat
            ;; the point; and the marker names a recovery command, because a
            ;; count alone tells the reader nothing it can act on. The excess
            ;; depends on the marker's own length, so the slice is solved by
            ;; bounded fixed-point iteration and asserted at or under budget by
            ;; a test rather than trusted.
            truncated? (> total budget)
            marker (fn [excess]
                     (str "\n... [TRUNCATED " excess " of " total " chars by the"
                          " document_read budget of " budget ". Read the rest:"
                          " shell sed -n 'A,Bp' " target "] ..."))
            kept (if truncated?
                   (loop [k (max 0 (- budget (count (marker 0)))) i 0]
                     (let [k2 (max 0 (- budget (count (marker (- total k)))))]
                       (if (or (= k2 k) (>= i 8)) k2 (recur k2 (inc i)))))
                   total)
            document {:path (str target)
                      :text (if truncated?
                              (str (subs text 0 kept) (marker (- total kept)))
                              text)
                      :total-chars total
                      :shown-chars kept
                      :truncated truncated?}]
        (common/ok-result "document_read"
                          {:path (str target)
                           :stdout (json/generate-string document)
                           :stderr ""
                           :exit/code 0
                           :document document})))))

(def handlers
  {"shell" run-shell!
   "git_status" git-status!
   "browser_cli" browser-cli!
   "document_read" document-read!})
