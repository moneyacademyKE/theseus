(ns bb-agent.tool.process
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [bb-agent.tool.path :as path]
            [cheshire.core :as json]
            [clojure.string :as str]))

;; The never-auto-run floor: these stay denied even under :auto-all with no
;; constitution present. Policy (brain/rules.clj) is the owner's law and
;; speaks first; this list only guards the policy-silent case. Everything
;; else the constitution grants (v5: chmod, mv, mutations on demand) runs.
(def ^:private never-auto-shell-prefixes
  ["rm " "rm\t" "rm\n" "dd " "sudo " "chown "])

(defn- never-auto-shell-command? [cmd]
  (let [trimmed (str/trim (or cmd ""))]
    (or (some #(str/starts-with? trimmed %) never-auto-shell-prefixes)
        (re-find #"(?:^|[;&|]\s*|\bcommand\s+)(rm|dd|sudo|chown)\b" trimmed))))

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
  (when-let [command (System/getenv "OPENCRABS_BROWSER_CLI")]
    [command url]))

(defn- browser-cli! [{:keys [url timeout-ms]}]
  (if (str/blank? (or url ""))
    (common/error-result "browser_cli" "browser_cli requires :url" {})
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
      (common/ok-result "browser_cli"
                        {:url url
                         :stdout ""
                         :stderr ""
                         :exit/code 0
                         :page {:url url
                                :title "fallback browser title"
                                :content "fallback browser content"}}))))

(defn- document-read! [{:keys [path]}]
  (let [target (path/checked-read-path "document_read" path)]
    (cond
      (= :error (:status target)) target

      (path/file-too-large? target common/max-read-bytes)
      (common/error-result "document_read"
                           (str "File too large: " path)
                           {:path path :max/bytes common/max-read-bytes})

      :else
      (let [text (slurp (str target))
            document {:path (str target)
                      :text (subs text 0 (min (count text) common/max-doc-chars))}]
        (common/ok-result "document_read"
                          {:path (str target)
                           :stdout (json/generate-string document)
                           :stderr ""
                           :exit/code 0
                           :document (or (parse-json-body (json/generate-string document))
                                         document)})))))

(def handlers
  {"shell" run-shell!
   "git_status" git-status!
   "browser_cli" browser-cli!
   "document_read" document-read!})
