(ns bb-agent.tool.path
  (:require [babashka.fs :as fs]
            [bb-agent.config :as config]
            [bb-agent.tool.common :as common]
            [clojure.string :as str])
  (:import [java.nio.file LinkOption]))

(defn- real-path [path]
  (.toRealPath (.toPath (fs/file path)) (make-array LinkOption 0)))

(defn- inside? [root target]
  (let [root (str root)
        target (str target)]
    (or (= root target)
        (str/starts-with? target (str root java.io.File/separator)))))

(defn allowed-root []
  (real-path (fs/path (config/home))))

(defn checked-read-path [tool-name path]
  (cond
    (str/blank? (or path ""))
    (common/error-result tool-name (str tool-name " requires :path") {})

    :else
    (let [target (fs/path path)]
      (cond
        (not (fs/exists? target))
        (common/error-result tool-name (str "File not found: " path) {:path path})

        (not (inside? (allowed-root) (real-path target)))
        (common/error-result tool-name
                             (str "Path is outside allowed root: " path)
                             {:path path :allowed/root (str (allowed-root))})

        :else target))))

(defn checked-write-path [tool-name path]
  (cond
    (str/blank? (or path ""))
    (common/error-result tool-name (str tool-name " requires :path") {})

    :else
    (let [target (fs/path path)
          parent (or (fs/parent target) (fs/path "."))]
      (cond
        (and (fs/exists? target)
             (not (inside? (allowed-root) (real-path target))))
        (common/error-result tool-name
                             (str "Path is outside allowed root: " path)
                             {:path path :allowed/root (str (allowed-root))})

        (and (fs/exists? parent)
             (not (inside? (allowed-root) (real-path parent))))
        (common/error-result tool-name
                             (str "Path is outside allowed root: " path)
                             {:path path :allowed/root (str (allowed-root))})

        :else target))))

(defn file-too-large? [path max-bytes]
  (> (fs/size path) max-bytes))
