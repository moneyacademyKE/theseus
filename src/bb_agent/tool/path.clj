(ns bb-agent.tool.path
  (:require [babashka.fs :as fs]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

;; Path tools are NOT jailed to the agent home (owner directive 2026-09-07:
;; "remove sandbox" — the home-root confinement prevented working outside the
;; agent's own folder). Structural validation lives here; policy lives in the
;; constitution (brain/rules.clj — secrets, rm, sudo, python stay floored).

(defn checked-read-path [tool-name path]
  (cond
    (str/blank? (or path ""))
    (common/error-result tool-name (str tool-name " requires :path") {})

    :else
    (let [target (fs/path path)]
      (if (fs/exists? target)
        target
        (common/error-result tool-name (str "File not found: " path) {:path path})))))

(defn checked-write-path [tool-name path]
  (if (str/blank? (or path ""))
    (common/error-result tool-name (str tool-name " requires :path") {})
    (fs/path path)))

(defn file-too-large? [path max-bytes]
  (> (fs/size path) max-bytes))
