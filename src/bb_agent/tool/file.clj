(ns bb-agent.tool.file
  (:require [babashka.fs :as fs]
            [bb-agent.tool.common :as common]
            [bb-agent.tool.path :as path]
            [clojure.string :as str]))

(defn- read-file! [{:keys [path]}]
  (let [target (path/checked-read-path "read_file" path)]
    (cond
      (= :error (:status target)) target

      (not (fs/exists? target))
      (common/error-result "read_file" (str "File not found: " path) {:path path})

      (fs/directory? target)
      (common/error-result "read_file" (str "Path is a directory: " path) {:path path})

      (path/file-too-large? target common/max-read-bytes)
      (common/error-result "read_file"
                           (str "File too large: " path)
                           {:path path :max/bytes common/max-read-bytes})

      :else
      (let [content (slurp (str target))]
        (common/ok-result "read_file"
                          {:path (str target)
                           :content content
                           :bytes (count (.getBytes content "UTF-8"))})))))

(defn- write-file! [{:keys [path content append? create-dirs?]}]
  (let [target (path/checked-write-path "write_file" path)
        content (or content "")
        bytes (count (.getBytes content "UTF-8"))]
    (cond
      (= :error (:status target)) target

      (> bytes common/max-write-bytes)
      (common/error-result "write_file"
                           (str "Content too large for write_file: " path)
                           {:path path :max/bytes common/max-write-bytes :bytes bytes})

      :else
      (do
        (when-let [parent (fs/parent target)]
          (when (or create-dirs? append?)
            (fs/create-dirs parent)))
        (if append?
          (spit (str target) content :append true)
          (do
            (when-let [parent (fs/parent target)]
              (when create-dirs?
                (fs/create-dirs parent)))
            (spit (str target) content)))
        (common/ok-result "write_file"
                          {:path (str target)
                           :bytes bytes
                           :append? (boolean append?)})))))

(defn- grep-line [query line-number line]
  {:line/number line-number
   :line/text line
   :match query})

(defn- search-file [file query]
  (let [checked (path/checked-read-path "search" (str file))]
    (if (= :error (:status checked))
      []
      (->> (str/split-lines (slurp (str checked)))
           (map-indexed (fn [idx line]
                          (when (str/includes? line query)
                            (grep-line query (inc idx) line))))
           (remove nil?)))))

(defn- search! [{:keys [path query]}]
  (let [base (path/checked-read-path "search" path)
        query (or query "")]
    (cond
      (str/blank? query)
      (common/error-result "search" "search requires non-empty :query" {})

      (= :error (:status base)) base

      (not (fs/exists? base))
      (common/error-result "search" (str "Search path not found: " path) {:path path})

      :else
      (let [files (if (fs/directory? base)
                    (->> (fs/glob base "**/*") (filter fs/regular-file?))
                    [base])
            matches (->> files
                         (mapcat (fn [file]
                                   (map #(assoc % :path (str file))
                                        (search-file file query))))
                         (take (common/bounded-count nil common/max-search-results))
                         vec)]
        (common/ok-result "search" {:path (str base)
                                    :query query
                                    :matches matches})))))

(def handlers
  {"read_file" read-file!
   "write_file" write-file!
   "search" search!})
