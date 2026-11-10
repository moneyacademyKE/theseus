(ns bb-agent.tool.web
  "web_send_file: deliver a local file into the web cockpit
  conversation — the web sibling of telegram_send_file. The artifact
  registers against the running web job (id injected by
  bb-agent.tool from :web/job-id) and the deck serves it as an
  authenticated download chip. Outside a web turn it refuses, exactly
  as telegram_send_file refuses outside Telegram."
  (:require [babashka.fs :as fs]
            [bb-agent.tool.common :as common]
            [bb-agent.web-artifacts :as artifacts]
            [clojure.string :as str]))

(defn- arg
  [args key]
  (or (get args key) (get args (name key))))

(defn send-file
  [args]
  (let [tool-name "web_send_file"
        job-id (arg args :web-job-id)]
    (if (str/blank? (str job-id))
      (common/error-result tool-name
                           "web_send_file is only available inside a web cockpit session"
                           {:executed? false})
      (try
        (let [raw-path (str (:path args))
              path (if (str/starts-with? raw-path "/")
                     raw-path
                     (str (fs/file (or (some-> (:cwd args) str) ".") raw-path)))
              art (artifacts/register! path job-id (arg args :caption))]
          (common/ok-result tool-name
                            {:artifact/id (:artifact/id art)
                             :name (:name art)
                             :bytes (:bytes art)
                             :output (str "sent " (:name art) " to the web deck ("
                                          (:bytes art) " bytes) — the user can download it")}))
        (catch Exception e
          (common/error-result tool-name
                               (or (ex-message e) (.getName (class e)))
                               {:exception/type (str (class e))
                                :executed? false}))))))

(def handlers
  {"web_send_file" send-file})
