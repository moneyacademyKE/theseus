(ns bb-agent.tool.path
  (:require [babashka.fs :as fs]
            [bb-agent.tool.common :as common]
            [clojure.string :as str]))

;; Path tools validate structure; approval mode decides whether a call runs.

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
